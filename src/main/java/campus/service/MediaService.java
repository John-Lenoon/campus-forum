package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.CurrentUser;
import campus.dto.MediaFile;
import campus.entity.ResourceEntity;
import campus.infrastructure.MediaTools;
import campus.infrastructure.ResourceStorage;
import campus.mapper.ChunkMapper;
import campus.mapper.PostMapper;
import campus.mapper.ResourceMapper;
import campus.mapper.UserMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaService {
    private final ResourceMapper resourceMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final ChunkMapper chunkMapper;
    private final Clock clock;
    private final MediaTools tools;
    private final ResourceStorage storage;
    private final TransactionTemplate transactions;

    public MediaService(
            ResourceMapper resourceMapper,
            PostMapper postMapper,
            UserMapper userMapper,
            ChunkMapper chunkMapper,
            Clock clock,
            MediaTools tools,
            ResourceStorage storage,
            PlatformTransactionManager transactionManager) {
        this.resourceMapper = resourceMapper;
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.chunkMapper = chunkMapper;
        this.clock = clock;
        this.tools = tools;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    ResourceEntity row(String id) {
        var row = resourceMapper.selectById(id);
        if (row == null) throw fail(404, "NOT_FOUND");
        return row;
    }

    /** HTTP upload entry: callers must not wrap it in an outer database transaction; the
     * reservation must commit before the file is created. */
    public Map<String, Object> image(CurrentUser user, String purpose, MultipartFile file) {
        if (!Set.of("POST", "AVATAR").contains(purpose)) throw fail(400, "INVALID_PURPOSE");
        long max = (purpose.equals("AVATAR") ? 5L : 10L) * 1024 * 1024;
        if (file.isEmpty() || file.getSize() > max) throw fail(400, "INVALID_IMAGE_SIZE");
        String id = UUID.randomUUID().toString();
        // Register a recoverable, non-ready resource before creating any persistent file.
        var resource = insertImage(id, user.id(), purpose, file.getSize());
        return transactions.execute(status -> {
            var rows = resourceMapper.lockById(id);
            if (rows.isEmpty() || !"UPLOADING".equals(rows.getFirst().getState())
                    || clock.millis() >= rows.getFirst().getExpiresAt())
                throw fail(410, "RESOURCE_EXPIRED");
            String mime;
            try {
                Path original = storage.storeImage(id, file);
                mime = tools.image(original, purpose.equals("AVATAR"));
            } catch (IOException e) {
                storage.removeDirectory(id);
                throw fail(503, "STORAGE_UNAVAILABLE");
            } catch (RuntimeException e) {
                storage.removeDirectory(id);
                throw e;
            }
            long now = clock.millis();
            if (now >= rows.getFirst().getExpiresAt()) {
                storage.removeDirectory(id);
                throw fail(410, "RESOURCE_EXPIRED");
            }
            long expiry = now + 86400000;
            if (resourceMapper.readyImage(id, mime, expiry) != 1) throw fail(409, "RESOURCE_NOT_READY");
            resource.setState("READY");
            resource.setMime(mime);
            resource.setExpiresAt(expiry);
            return view(resource);
        });
    }

    public void visible(ResourceEntity resource, CurrentUser user) {
        if (resource.getRetiredAt() != null) throw fail(404, "NOT_FOUND");
        if (resource.getPostId() != null) {
            var post = postMapper.selectById(resource.getPostId());
            if (post == null || post.getDeletedAt() != null) throw fail(404, "NOT_FOUND");
            if (!"PUBLISHED".equals(post.getStatus())
                    && (user == null
                            || (!user.role().equals("ADMIN")
                                    && !user.id().equals(post.getAuthorId()))))
                throw fail(404, "NOT_FOUND");
        } else if (resource.getAvatarUserId() == null) {
            if (user == null || !user.id().equals(resource.getOwnerId()))
                throw fail(404, "NOT_FOUND");
            if (resource.getExpiresAt() != null && clock.millis() >= resource.getExpiresAt())
                throw fail(410, "RESOURCE_EXPIRED");
        }
        // An image reservation is internal until its validated metadata and file are committed.
        if ("IMAGE".equals(resource.getKind()) && "UPLOADING".equals(resource.getState()))
            throw fail(404, "NOT_FOUND");
    }

    public Map<String, Object> view(ResourceEntity resource) {
        var response = new LinkedHashMap<String, Object>();
        String id = resource.getId();
        response.put("id", id);
        response.put("type", resource.getKind());
        response.put("state", resource.getState());
        response.put("size", resource.getSizeBytes());
        response.put("mime", resource.getMime());
        response.put(
                "url",
                "/api/v1/resources/"
                        + id
                        + "/files/"
                        + ("VIDEO".equals(resource.getKind()) ? "index.m3u8" : "original"));
        response.put("originalUrl", "/api/v1/resources/" + id + "/files/original");
        response.put("expiresAt", resource.getExpiresAt());
        response.put("errorCode", resource.getErrorCode());
        return response;
    }

    public Map<String, Object> info(String id, CurrentUser user) {
        var resource = row(id);
        visible(resource, user);
        var response = view(resource);
        if ("VIDEO".equals(resource.getKind())) {
            response.put("duration", resource.getDurationSeconds());
            response.put("chunkSize", resource.getChunkSize());
            response.put("totalParts", resource.getPartsTotal());
            response.put("uploadedParts", chunkMapper.uploadedIndices(id));
        }
        return response;
    }

    public Path file(String id, String filename, CurrentUser user) {
        var resource = row(id);
        visible(resource, user);
        if (!"READY".equals(resource.getState())) throw fail(409, "RESOURCE_NOT_READY");
        if (!filename.equals("original")
                && !("VIDEO".equals(resource.getKind())
                        && (filename.equals("index.m3u8") || filename.matches("part[0-9]+\\.ts"))))
            throw fail(404, "NOT_FOUND");
        return storage.existingFile(resource.getId(), filename);
    }

    /**
     * Called within the post creation or edit transaction. For an existing post, the caller must
     * lock the post first; requested resources are then locked in ID order before any binding or
     * retirement is committed.
     */
    public void bindPost(CurrentUser owner, String postId, List<String> requested) {
        List<String> ids = requested == null ? List.of() : requested;
        if (ids.size() > 9
                || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()) throw fail(400, "INVALID_RESOURCES");
        int videos = 0, images = 0;
        for (String id : ids.stream().sorted().toList()) {
            var rows = resourceMapper.lockById(id);
            if (rows.isEmpty()) throw fail(400, "INVALID_RESOURCE");
            var resource = rows.getFirst();
            if (!owner.id().equals(resource.getOwnerId())
                    || !"POST".equals(resource.getPurpose())
                    || !"READY".equals(resource.getState())
                    || resource.getRetiredAt() != null
                    || (resource.getPostId() != null && !postId.equals(resource.getPostId()))
                    || (resource.getPostId() == null
                            && Boolean.TRUE.equals(resource.getEverBound()))
                    || (resource.getPostId() == null
                            && resource.getExpiresAt() != null
                            && clock.millis() >= resource.getExpiresAt()))
                throw fail(400, "INVALID_RESOURCE");
            if ("VIDEO".equals(resource.getKind())) videos++;
            else images++;
        }
        if (videos > 1 || (videos > 0 && images > 0)) throw fail(400, "MIXED_MEDIA");
        for (var old : resourceMapper.activePostIds(postId))
            if (!ids.contains(old)) resourceMapper.retire(clock.millis(), old);
        for (String id : ids) resourceMapper.bindPost(postId, id);
    }

    public List<Map<String, Object>> attachments(String id) {
        return resourceMapper.findAttachments(id).stream().map(this::view).toList();
    }

    /** Called within post deletion, after the caller locks the post and in the same transaction. */
    public void retirePost(String id) {
        resourceMapper.retirePost(clock.millis(), id);
    }

    /**
     * Called within the profile update transaction. Lock the user before the target resource so
     * avatar retirement, binding and the profile update commit together.
     */
    public void bindAvatar(CurrentUser user, String id) {
        var current = userMapper.lockById(user.id());
        var records = resourceMapper.lockById(id);
        if (records.isEmpty()) throw fail(400, "INVALID_AVATAR");
        var resource = records.getFirst();
        if (!user.id().equals(resource.getOwnerId())
                || !"AVATAR".equals(resource.getPurpose())
                || !"IMAGE".equals(resource.getKind())
                || !"READY".equals(resource.getState())
                || resource.getRetiredAt() != null
                || (resource.getExpiresAt() != null && clock.millis() >= resource.getExpiresAt()))
            throw fail(400, "INVALID_AVATAR");
        if (current.getAvatarResourceId() != null && !id.equals(current.getAvatarResourceId()))
            resourceMapper.retire(clock.millis(), current.getAvatarResourceId());
        resourceMapper.bindAvatar(user.id(), id);
        userMapper.updateAvatar(id, user.id());
    }

    public MediaFile download(String id, String filename, CurrentUser user) {
        Path path = file(id, filename, user);
        String mime =
                filename.endsWith(".m3u8")
                        ? "application/vnd.apple.mpegurl"
                        : filename.endsWith(".ts") ? "video/mp2t" : row(id).getMime();
        return new MediaFile(path, mime);
    }

    private ResourceEntity insertImage(String id, String ownerId, String purpose, long size) {
        var resource = new ResourceEntity();
        resource.setId(id);
        resource.setOwnerId(ownerId);
        resource.setPurpose(purpose);
        resource.setKind("IMAGE");
        resource.setState("UPLOADING");
        resource.setSizeBytes(size);
        resource.setCreatedAt(clock.millis());
        resource.setExpiresAt(clock.millis() + 86400000);
        resourceMapper.insert(resource);
        return resource;
    }

    @Transactional
    public Map<String, Object> cleanup() {
        long now = clock.millis();
        var candidates = resourceMapper.lockExpired(now - 86400000, now);
        for (String id : candidates) {
            storage.removeDirectory(id);
            resourceMapper.markExpired(id);
        }
        return Map.of("removed", candidates.size());
    }
}
