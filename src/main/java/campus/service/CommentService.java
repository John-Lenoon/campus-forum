package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.CurrentUser;
import campus.entity.CommentEntity;
import campus.mapper.CommentMapper;
import campus.mapper.PostMapper;
import campus.support.TextValidation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CommentService {
    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final Clock clock;
    private final PostService posts;
    private final UserService users;

    public CommentService(
            CommentMapper commentMapper,
            PostMapper postMapper,
            Clock clock,
            PostService posts,
            UserService users) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.clock = clock;
        this.posts = posts;
        this.users = users;
    }

    private CommentEntity row(String id) {
        var rows = commentMapper.selectById(id);
        if (rows == null) throw fail(404, "NOT_FOUND");
        return rows;
    }

    private Map<String, Object> view(CommentEntity comment, boolean history) {
        var response = new LinkedHashMap<String, Object>();
        String id = comment.getId();
        response.put("id", id);
        response.put("postId", comment.getPostId());
        response.put("body", comment.getBody());
        response.put("createdAt", comment.getCreatedAt());
        response.put("rootId", comment.getRootId());
        response.put("replyToId", comment.getReplyToId());
        if (history) {
            response.put("deleted", comment.getDeletedAt() != null);
            if (comment.getDeletedAt() != null) response.put("message", "该评论已删除");
            return response;
        }
        response.put("author", users.publicUser(comment.getUserId()));
        boolean targetDeleted = false;
        if (comment.getReplyToId() != null)
            targetDeleted = row(comment.getReplyToId()).getDeletedAt() != null;
        response.put("replyTargetDeleted", targetDeleted);
        if (targetDeleted) response.put("replyTargetMessage", "该评论已删除");
        if (comment.getRootId() == null) response.put("replyCount", commentMapper.countReplies(id));
        return response;
    }

    @Transactional
    public Map<String, Object> create(String postId, String target, CurrentUser user, String body) {
        TextValidation.text(body, 1, 1000, "INVALID_COMMENT");
        if (target != null) postId = (String) row(target).getPostId();
        var p = postMapper.lockActive(postId);
        if (p.isEmpty() || !"PUBLISHED".equals(p.getFirst().getStatus()))
            throw fail(404, "NOT_FOUND");
        String root = null;
        if (target != null) {
            // A separate statement prevents reuse of the pre-lock selectById session cache.
            var parent = commentMapper.findReplyTarget(target);
            if (parent == null || parent.getDeletedAt() != null) throw fail(404, "NOT_FOUND");
            root = (String) (parent.getRootId() == null ? parent.getId() : parent.getRootId());
        }
        String id = UUID.randomUUID().toString();
        insertComment(id, postId, user.id(), root, target, body);
        return view(row(id), false);
    }

    public Map<String, Object> detail(String id, CurrentUser user) {
        var comment = row(id);
        if (comment.getDeletedAt() != null) {
            if (user != null && user.id().equals(comment.getUserId()))
                throw fail(410, "COMMENT_DELETED");
            throw fail(404, "NOT_FOUND");
        }
        posts.detail(comment.getPostId(), user);
        return view(comment, false);
    }

    public Map<String, Object> list(
            String postId, String root, CurrentUser user, int page, int size, String order) {
        paging(page, size);
        if (!Set.of("asc", "desc").contains(order)) throw fail(400, "INVALID_ORDER");
        if (root != null) {
            var comment = row(root);
            if (comment.getRootId() != null || comment.getDeletedAt() != null)
                throw fail(404, "NOT_FOUND");
            postId = comment.getPostId();
        }
        posts.detail(postId, user);
        var items =
                commentMapper
                        .pageThread(postId, root, order, size, ((long) page - 1) * size)
                        .stream()
                        .map(comment -> view(comment, false))
                        .toList();
        long total = commentMapper.countThread(postId, root);
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    public Map<String, Object> history(CurrentUser user, int page, int size) {
        paging(page, size);
        var items =
                commentMapper.pageHistory(user.id(), size, ((long) page - 1) * size).stream()
                        .map(comment -> view(comment, true))
                        .toList();
        long total = commentMapper.countHistory(user.id());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    @Transactional
    public Map<String, Object> delete(String id, CurrentUser user) {
        var comment = row(id);
        var p = postMapper.lockActive(comment.getPostId());
        if (p.isEmpty() || (!user.id().equals(comment.getUserId()) && !user.role().equals("ADMIN")))
            throw fail(404, "NOT_FOUND");
        if (comment.getRootId() == null) commentMapper.deleteThread(clock.millis(), id);
        else commentMapper.deleteReply(clock.millis(), id);
        return Map.of("deleted", true);
    }

    private void insertComment(
            String id, String postId, String userId, String rootId, String target, String body) {
        var comment = new CommentEntity();
        comment.setId(id);
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setRootId(rootId);
        comment.setReplyToId(target);
        comment.setBody(body);
        comment.setCreatedAt(clock.millis());
        commentMapper.insert(comment);
    }

    private void paging(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw fail(400, "INVALID_PAGE");
    }
}
