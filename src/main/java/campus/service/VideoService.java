package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.CurrentUser;
import campus.dto.VideoPart;
import campus.entity.ChunkEntity;
import campus.entity.ResourceEntity;
import campus.exception.BusinessException;
import campus.infrastructure.VideoFiles;
import campus.mapper.ChunkMapper;
import campus.mapper.ResourceMapper;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Service
public class VideoService {
    static final int CHUNK = 8 * 1024 * 1024;
    private final MediaService media;
    private final RabbitTemplate rabbit;
    private final Queue queue;
    private final ResourceMapper resourceMapper;
    private final ChunkMapper chunkMapper;
    private final Clock clock;
    private final VideoFiles files;

    public VideoService(
            MediaService media,
            RabbitTemplate rabbit,
            Queue videoQueue,
            ResourceMapper resourceMapper,
            ChunkMapper chunkMapper,
            Clock clock,
            VideoFiles files) {
        this.media = media;
        this.rabbit = rabbit;
        this.queue = videoQueue;
        this.resourceMapper = resourceMapper;
        this.chunkMapper = chunkMapper;
        this.clock = clock;
        this.files = files;
    }

    @Transactional
    public Map<String, Object> start(CurrentUser user, long size) {
        if (size < 1 || size > 1073741824L) throw fail(400, "INVALID_VIDEO_SIZE");
        String id = UUID.randomUUID().toString();
        int parts = (int) ((size + CHUNK - 1) / CHUNK);
        insertVideo(id, user.id(), size, parts);
        return media.info(id, user);
    }

    private ResourceEntity owned(String id, CurrentUser user) {
        var rows = resourceMapper.lockById(id);
        if (rows.isEmpty()
                || !user.id().equals(rows.getFirst().getOwnerId())
                || !"VIDEO".equals(rows.getFirst().getKind())) throw fail(404, "NOT_FOUND");
        var resource = rows.getFirst();
        media.visible(resource, user);
        return resource;
    }

    @Transactional
    public Map<String, Object> chunk(String id, int index, CurrentUser user, InputStream input) {
        var resource = owned(id, user);
        if (!"UPLOADING".equals(resource.getState())) throw fail(409, "UPLOAD_CLOSED");
        if (index < 0 || index >= resource.getPartsTotal()) throw fail(400, "INVALID_PART");
        long expected = Math.min(CHUNK, resource.getSizeBytes() - (long) index * CHUNK);
        try (var received = files.receive(id, expected, input)) {
            var previous = chunkMapper.findPart(id, index);
            if (previous.isEmpty() || !received.sha256().equals(previous.getFirst().getSha256())) {
                received.retain(index);
                chunkMapper.deletePart(id, index);
                insertChunk(id, index, received.size(), received.sha256());
                resourceMapper.extendUpload(clock.millis() + 86400000, id);
            }
            return media.info(id, user);
        } catch (IOException e) {
            throw fail(503, "STORAGE_UNAVAILABLE");
        }
    }

    @Transactional
    public Map<String, Object> complete(String id, CurrentUser user) {
        var resource = owned(id, user);
        String state = resource.getState();
        if (state.equals("PROCESSING") || state.equals("READY") || state.equals("FAILED"))
            return media.info(id, user);
        if (!state.equals("UPLOADING")) throw fail(409, "UPLOAD_CLOSED");
        var parts = chunkMapper.allParts(id);
        if (parts.size() != resource.getPartsTotal()) throw fail(400, "UPLOAD_INCOMPLETE");
        try {
            var inputs =
                    parts.stream()
                            .map(
                                    part ->
                                            new VideoPart(
                                                    part.getPartIndex(),
                                                    part.getSizeBytes(),
                                                    part.getSha256()))
                            .toList();
            files.assemble(id, inputs, resource.getSizeBytes());
            resourceMapper.beginProcessing(id);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dispatch(id);
                        }
                    });
            return media.info(id, user);
        } catch (IOException e) {
            throw fail(503, "STORAGE_UNAVAILABLE");
        }
    }

    private void insertVideo(String id, String ownerId, long size, int parts) {
        var resource = new ResourceEntity();
        resource.setId(id);
        resource.setOwnerId(ownerId);
        resource.setPurpose("POST");
        resource.setKind("VIDEO");
        resource.setState("UPLOADING");
        resource.setMime("video/mp4");
        resource.setSizeBytes(size);
        resource.setCreatedAt(clock.millis());
        resource.setExpiresAt(clock.millis() + 86400000);
        resource.setChunkSize(CHUNK);
        resource.setPartsTotal(parts);
        resourceMapper.insert(resource);
    }

    private void insertChunk(String id, int index, long size, String hash) {
        var chunk = new ChunkEntity();
        chunk.setResourceId(id);
        chunk.setPartIndex(index);
        chunk.setSizeBytes((int) size);
        chunk.setSha256(hash);
        chunkMapper.insertPart(chunk);
    }

    private void dispatch(String id) {
        try {
            rabbit.convertAndSend(queue.getName(), id);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(VideoService.class)
                    .warn("Video dispatch deferred; durable database job remains pending: {}", id);
        }
    }

    public void recover() {
        for (String id : resourceMapper.recoverableIds(clock.millis())) dispatch(id);
    }

    public void process(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) return;
        String claim = UUID.randomUUID().toString();
        int updated =
                resourceMapper.claimProcessing(claim, clock.millis() + 7500000, id, clock.millis());
        if (updated == 0) return;
        double duration;
        try {
            duration = files.createPlayback(id, claim);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String code = e instanceof BusinessException f ? f.code() : "PROCESSING_FAILED";
            resourceMapper.failProcessing(code, clock.millis() + 86400000, id, claim);
            return;
        }
        // A database error can arrive after READY committed; do not turn it into a media failure.
        resourceMapper.finishProcessing(duration, clock.millis() + 86400000, id, claim);
    }
}
