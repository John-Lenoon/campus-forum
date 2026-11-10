package campus.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("forum_resources")
public class ResourceEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String ownerId;
    private String purpose;
    private String kind;
    private String state;
    private String mime;
    private Long sizeBytes;
    private Double durationSeconds;
    private String postId;
    private String avatarUserId;
    private Boolean everBound;
    private Long createdAt;
    private Long expiresAt;
    private Long retiredAt;
    private Integer chunkSize;
    private Integer partsTotal;
    private String processingClaim;
    private Long claimUntil;
    private String errorCode;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Double getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Double durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getAvatarUserId() {
        return avatarUserId;
    }

    public void setAvatarUserId(String avatarUserId) {
        this.avatarUserId = avatarUserId;
    }

    public Boolean getEverBound() {
        return everBound;
    }

    public void setEverBound(Boolean everBound) {
        this.everBound = everBound;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getRetiredAt() {
        return retiredAt;
    }

    public void setRetiredAt(Long retiredAt) {
        this.retiredAt = retiredAt;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Integer getPartsTotal() {
        return partsTotal;
    }

    public void setPartsTotal(Integer partsTotal) {
        this.partsTotal = partsTotal;
    }

    public String getProcessingClaim() {
        return processingClaim;
    }

    public void setProcessingClaim(String processingClaim) {
        this.processingClaim = processingClaim;
    }

    public Long getClaimUntil() {
        return claimUntil;
    }

    public void setClaimUntil(Long claimUntil) {
        this.claimUntil = claimUntil;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
