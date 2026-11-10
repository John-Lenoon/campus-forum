package campus.dto;

import java.util.List;

public record EditPostRequest(
        Integer revision, String title, String body, List<String> resourceIds) {}
