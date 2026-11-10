package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.HotSnapshot;
import campus.dto.PostCard;
import campus.exception.BusinessException;
import campus.mapper.PostMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@Service
public class HotFeedService {
    private final StringRedisTemplate redis;
    private final PostMapper postMapper;
    private final PostService posts;
    private final ObjectMapper json;
    private final Clock clock;

    public HotFeedService(
            StringRedisTemplate redis,
            PostMapper postMapper,
            PostService posts,
            ObjectMapper json,
            Clock clock) {
        this.redis = redis;
        this.postMapper = postMapper;
        this.posts = posts;
        this.json = json;
        this.clock = clock;
    }

    public Map<String, Object> list(String snapshotId, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw fail(400, "INVALID_PAGE");
        try {
            HotSnapshot snapshot;
            if (snapshotId == null) {
                if (page != 1) throw fail(400, "SNAPSHOT_REQUIRED");
                var ids = postMapper.rankPublished();
                snapshotId = UUID.randomUUID().toString();
                snapshot = new HotSnapshot(ids, clock.millis() + 600000);
                redis.opsForValue()
                        .set(
                                "campus-forum:hot:" + snapshotId,
                                json.writeValueAsString(snapshot),
                                Duration.ofMinutes(10));
            } else {
                if (!snapshotId.matches("[a-f0-9-]{36}")) throw fail(410, "REFRESH_REQUIRED");
                String encoded = redis.opsForValue().get("campus-forum:hot:" + snapshotId);
                if (encoded == null) throw fail(410, "REFRESH_REQUIRED");
                snapshot = json.readValue(encoded, HotSnapshot.class);
                if (clock.millis() >= snapshot.expiresAt()) throw fail(410, "REFRESH_REQUIRED");
            }
            long start = ((long) page - 1) * size;
            var items = new ArrayList<PostCard>();
            for (long i = start; i < Math.min(start + size, snapshot.ids().size()); i++) {
                try {
                    items.add(posts.publicCard(snapshot.ids().get((int) i)));
                } catch (BusinessException e) {
                    if (e.status() != 404) throw e;
                }
            }
            return Map.of(
                    "snapshotId",
                    snapshotId,
                    "expiresAt",
                    snapshot.expiresAt(),
                    "items",
                    items,
                    "page",
                    page,
                    "size",
                    size,
                    "total",
                    snapshot.ids().size(),
                    "hasMore",
                    start + size < snapshot.ids().size());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw fail(503, "SNAPSHOT_UNAVAILABLE");
        }
    }
}
