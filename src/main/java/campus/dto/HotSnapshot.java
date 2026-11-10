package campus.dto;

import java.util.List;

public record HotSnapshot(List<String> ids, long expiresAt) {}
