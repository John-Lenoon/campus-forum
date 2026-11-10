package campus.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("forum_users")
public class UserEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String username;
    private String usernameKey;
    private String passwordHash;
    private String nickname;
    private String role;
    private Long createdAt;
    private String avatarResourceId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsernameKey() {
        return usernameKey;
    }

    public void setUsernameKey(String usernameKey) {
        this.usernameKey = usernameKey;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public String getAvatarResourceId() {
        return avatarResourceId;
    }

    public void setAvatarResourceId(String avatarResourceId) {
        this.avatarResourceId = avatarResourceId;
    }
}
