package campus.dto;

public record LoginRequest(String username, String password) {
    public LoginRequest {
        if (username == null || password == null) {
            throw new IllegalArgumentException("username and password are required");
        }
    }
}
