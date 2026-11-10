package campus.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("forum_posts")
public class PostEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String authorId;
    private String title;
    private String body;
    private String status;
    private Integer revision;
    private Long submittedAt;
    private Long firstPublishedAt;
    private String rejectionReason;
    private String reviewedBy;
    private Long reviewedAt;
    private Long deletedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAuthorId() {
        return authorId;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public Long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Long getFirstPublishedAt() {
        return firstPublishedAt;
    }

    public void setFirstPublishedAt(Long firstPublishedAt) {
        this.firstPublishedAt = firstPublishedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Long getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Long reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
