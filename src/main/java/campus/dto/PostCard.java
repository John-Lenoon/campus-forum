package campus.dto;

import java.util.List;
import java.util.Map;

/** Public projection: personal interaction state and full body belong to the detail endpoint. */
public record PostCard(
        String id,
        String title,
        String status,
        Integer revision,
        Long submittedAt,
        Long firstPublishedAt,
        PublicUser author,
        Long likeCount,
        Long favoriteCount,
        List<Map<String, Object>> attachments) {}
