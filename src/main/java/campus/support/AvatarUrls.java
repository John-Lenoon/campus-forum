package campus.support;

public final class AvatarUrls {
    public static final String DEFAULT = "/assets/default-avatar.svg";

    private AvatarUrls() {}

    public static String forResource(String resourceId) {
        return resourceId == null ? DEFAULT : "/api/v1/resources/" + resourceId + "/files/original";
    }
}
