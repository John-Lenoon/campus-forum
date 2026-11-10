package campus.entity;

public class PostView extends PostEntity {
    private String nickname;
    private String avatarResourceId;
    private Long likeCount;
    private Long favoriteCount;

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatarResourceId() {
        return avatarResourceId;
    }

    public void setAvatarResourceId(String avatarResourceId) {
        this.avatarResourceId = avatarResourceId;
    }

    public Long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }

    public Long getFavoriteCount() {
        return favoriteCount;
    }

    public void setFavoriteCount(Long favoriteCount) {
        this.favoriteCount = favoriteCount;
    }
}
