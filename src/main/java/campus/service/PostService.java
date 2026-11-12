package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.CurrentUser;
import campus.dto.PostCard;
import campus.dto.PublicUser;
import campus.entity.PostEntity;
import campus.entity.PostView;
import campus.mapper.CommentMapper;
import campus.mapper.InteractionMapper;
import campus.mapper.PostMapper;
import campus.support.AvatarUrls;
import campus.support.TextValidation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PostService {
    private final PostMapper postMapper;
    private final InteractionMapper interactionMapper;
    private final CommentMapper commentMapper;
    private final Clock clock;
    private final MediaService media;

    public PostService(
            PostMapper postMapper,
            InteractionMapper interactionMapper,
            CommentMapper commentMapper,
            Clock clock,
            MediaService media) {
        this.postMapper = postMapper;
        this.interactionMapper = interactionMapper;
        this.commentMapper = commentMapper;
        this.clock = clock;
        this.media = media;
    }

    private Map<String, Object> view(PostView row) {
        var post = new LinkedHashMap<String, Object>();
        post.put("id", row.getId());
        post.put("title", row.getTitle());
        post.put("body", row.getBody());
        post.put("status", row.getStatus());
        post.put("revision", row.getRevision());
        post.put("submittedAt", row.getSubmittedAt());
        post.put("firstPublishedAt", row.getFirstPublishedAt());
        post.put("rejectionReason", row.getRejectionReason());
        post.put(
                "author",
                Map.of(
                        "id",
                        row.getAuthorId(),
                        "nickname",
                        row.getNickname(),
                        "avatarUrl",
                        AvatarUrls.forResource(row.getAvatarResourceId())));
        post.put("likeCount", row.getLikeCount());
        post.put("favoriteCount", row.getFavoriteCount());
        return post;
    }

    private void insertPost(String id, String authorId, String title, String body) {
        var post = new PostEntity();
        post.setId(id);
        post.setAuthorId(authorId);
        post.setTitle(title);
        post.setBody(body);
        post.setStatus("PENDING");
        post.setRevision(1);
        post.setSubmittedAt(clock.millis());
        postMapper.insert(post);
    }

    @Transactional
    public Map<String, Object> create(
            CurrentUser user, String title, String body, List<String> resourceIds) {
        if (resourceIds == null) throw fail(400, "INVALID_RESOURCES");
        TextValidation.text(title, 1, 100, "INVALID_TITLE");
        TextValidation.text(body, 1, 10000, "INVALID_BODY");
        String id = UUID.randomUUID().toString();
        insertPost(id, user.id(), title, body);
        media.bindPost(user, id, resourceIds);
        return detail(id, user);
    }

    public Map<String, Object> detail(String id, CurrentUser user) {
        return detail(id, user, false);
    }

    public Map<String, Object> publishedDetail(String id, CurrentUser user) {
        return detail(id, user, true);
    }

    private Map<String, Object> detail(String id, CurrentUser user, boolean publishedOnly) {
        var rows = postMapper.findView(id);
        if (rows.isEmpty()) throw fail(404, "NOT_FOUND");
        var row = rows.getFirst();
        if (!"PUBLISHED".equals(row.getStatus())
                && (publishedOnly || user == null
                        || (!"ADMIN".equals(user.role()) && !user.id().equals(row.getAuthorId()))))
            throw fail(404, "NOT_FOUND");
        var p = view(row);
        if ("PUBLISHED".equals(row.getStatus())) p.remove("rejectionReason");
        p.put("liked", hasInteraction(id, user, "likes"));
        p.put("favorited", hasInteraction(id, user, "favorites"));
        p.put("attachments", media.attachments(id));
        return p;
    }

    private boolean hasInteraction(String id, CurrentUser user, String kind) {
        return user != null && interactionMapper.countRelation(user.id(), id, kind) > 0;
    }

    public PostCard publicCard(String id) {
        var rows = postMapper.findView(id);
        if (rows.isEmpty() || !"PUBLISHED".equals(rows.getFirst().getStatus()))
            throw fail(404, "NOT_FOUND");
        return card(rows.getFirst());
    }

    private PostCard card(PostView post) {
        return new PostCard(
                post.getId(),
                post.getTitle(),
                post.getStatus(),
                post.getRevision(),
                post.getSubmittedAt(),
                post.getFirstPublishedAt(),
                new PublicUser(
                        post.getAuthorId(),
                        post.getNickname(),
                        AvatarUrls.forResource(post.getAvatarResourceId())),
                post.getLikeCount(),
                post.getFavoriteCount(),
                media.attachments(post.getId()));
    }

    public Map<String, Object> list(boolean admin, String status, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw fail(400, "INVALID_PAGE");
        if (status != null && !Set.of("PENDING", "PUBLISHED", "REJECTED").contains(status))
            throw fail(400, "INVALID_STATUS");
        String filter = admin ? status : "PUBLISHED";
        long count = postMapper.countVisible(filter);
        var rows = postMapper.pageViews(filter, admin, size, ((long) page - 1) * size);
        List<?> items =
                admin
                        ? rows.stream().map(this::view).toList()
                        : rows.stream().map(this::card).toList();
        return Map.of("items", items, "page", page, "size", size, "total", count);
    }

    public Map<String, Object> listMine(CurrentUser user, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw fail(400, "INVALID_PAGE");
        var rows = postMapper.pageByAuthor(user.id(), size, ((long) page - 1) * size);
        var items = rows.stream().map(this::view).toList();
        return Map.of("items", items, "page", page, "size", size, "total", postMapper.countByAuthor(user.id()));
    }

    @Transactional
    public Map<String, Object> review(
            String id, CurrentUser admin, Integer revision, String decision, String reason) {
        if (revision == null
                || revision < 1
                || !("APPROVE".equals(decision) || "REJECT".equals(decision)))
            throw fail(400, "INVALID_REVIEW");
        if ("REJECT".equals(decision)) TextValidation.text(reason, 1, 1000, "INVALID_REASON");
        else if (reason != null) throw fail(400, "INVALID_REVIEW");
        String target = "APPROVE".equals(decision) ? "PUBLISHED" : "REJECTED";
        var rows = postMapper.lockActive(id);
        if (rows.isEmpty()) throw fail(404, "NOT_FOUND");
        var row = rows.getFirst();
        if (!row.getRevision().equals(revision)) throw fail(409, "STALE_REVISION");
        String status = row.getStatus();
        if (!"PENDING".equals(status)) {
            if (target.equals(status)) return detail(id, admin);
            throw fail(409, "REVIEW_CONFLICT");
        }
        postMapper.review(target, clock.millis(), reason, admin.id(), clock.millis(), id);
        return detail(id, admin);
    }

    @Transactional
    public Map<String, Object> edit(
            String id,
            CurrentUser owner,
            Integer revision,
            String title,
            String body,
            List<String> resourceIds) {
        if (revision == null || revision < 1) throw fail(400, "INVALID_REVISION");
        if (resourceIds == null) throw fail(400, "INVALID_RESOURCES");
        TextValidation.text(title, 1, 100, "INVALID_TITLE");
        TextValidation.text(body, 1, 10000, "INVALID_BODY");
        var current = lockOwned(id, owner, false);
        if (!current.getRevision().equals(revision)) throw fail(409, "STALE_REVISION");
        media.bindPost(owner, id, resourceIds);
        postMapper.edit(title, body, clock.millis(), id);
        return detail(id, owner);
    }

    @Transactional
    public Map<String, Object> delete(String id, CurrentUser user) {
        lockOwned(id, user, true);
        postMapper.markDeleted(clock.millis(), id);
        commentMapper.deleteForPost(clock.millis(), id);
        media.retirePost(id);
        return Map.of("deleted", true);
    }

    private PostEntity lockOwned(String id, CurrentUser user, boolean allowAdmin) {
        var rows = postMapper.lockActive(id);
        if (rows.isEmpty()
                || (!user.id().equals(rows.getFirst().getAuthorId())
                        && !(allowAdmin && "ADMIN".equals(user.role()))))
            throw fail(404, "NOT_FOUND");
        return rows.getFirst();
    }
}
