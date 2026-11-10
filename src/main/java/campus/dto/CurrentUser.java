package campus.dto;

import java.util.Map;

public record CurrentUser(String id, String nickname, String role, String avatarUrl) {
    public Map<String, Object> view() {
        return Map.of("id", id, "nickname", nickname, "role", role, "avatarUrl", avatarUrl);
    }
}
