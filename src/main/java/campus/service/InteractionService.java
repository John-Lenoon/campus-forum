package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.CurrentUser;
import campus.entity.InteractionEntity;
import campus.exception.BusinessException;
import campus.mapper.InteractionMapper;
import campus.mapper.PostMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

@Service
public class InteractionService {
    private final InteractionMapper interactionMapper;
    private final PostMapper postMapper;
    private final Clock clock;
    private final PostService posts;

    public InteractionService(
            InteractionMapper interactionMapper,
            PostMapper postMapper,
            Clock clock,
            PostService posts) {
        this.interactionMapper = interactionMapper;
        this.postMapper = postMapper;
        this.clock = clock;
        this.posts = posts;
    }

    @Transactional
    public Map<String, Object> change(String id, CurrentUser user, String kind, boolean active) {
        checkKind(kind);
        var rows = postMapper.lockActive(id);
        if (rows.isEmpty() || (active && !"PUBLISHED".equals(rows.getFirst().getStatus())))
            throw fail(404, "NOT_FOUND");
        if (active) {
            if (interactionMapper.countRelation(user.id(), id, kind) == 0)
                insertRelation(user.id(), id, kind);
        } else interactionMapper.deleteRelation(user.id(), id, kind);
        return Map.of("active", active);
    }

    public Map<String, Object> list(CurrentUser user, String kind, int page, int size) {
        checkKind(kind);
        if (page < 1 || size < 1 || size > 100) throw fail(400, "INVALID_PAGE");
        long total = interactionMapper.countPublished(user.id(), kind);
        var ids =
                interactionMapper.pagePublishedIds(user.id(), kind, size, ((long) page - 1) * size);
        var items = new ArrayList<Map<String, Object>>();
        for (String id : ids) {
            try {
                items.add(posts.publishedDetail(id, user));
            } catch (BusinessException e) {
                if (e.status() != 404) throw e;
            }
        }
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    private void insertRelation(String userId, String postId, String kind) {
        var interaction = new InteractionEntity();
        interaction.setUserId(userId);
        interaction.setPostId(postId);
        interaction.setKind(kind);
        interaction.setCreatedAt(clock.millis());
        interactionMapper.insertRelation(interaction);
    }

    private void checkKind(String kind) {
        if (!Set.of("likes", "favorites").contains(kind)) throw fail(404, "NOT_FOUND");
    }
}
