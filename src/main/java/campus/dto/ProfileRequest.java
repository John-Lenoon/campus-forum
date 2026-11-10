package campus.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

public final class ProfileRequest {
    private String nickname;
    private String avatarResourceId;

    public String nickname() {
        return nickname;
    }

    public String avatarResourceId() {
        return avatarResourceId;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setAvatarResourceId(String avatarResourceId) {
        this.avatarResourceId = avatarResourceId;
    }
}
