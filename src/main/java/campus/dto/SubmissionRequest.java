package campus.dto;

import java.util.List;

public final class SubmissionRequest {
    private String title;
    private String body;
    private List<String> resourceIds = List.of();

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public List<String> resourceIds() {
        return resourceIds;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public void setResourceIds(List<String> resourceIds) {
        this.resourceIds = resourceIds;
    }
}
