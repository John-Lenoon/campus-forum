package campus.controller;

import campus.service.MediaService;
import campus.service.SessionService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ResourceMaintenanceController {
    private final MediaService media;
    private final SessionService sessions;

    ResourceMaintenanceController(MediaService media, SessionService sessions) {
        this.media = media;
        this.sessions = sessions;
    }

    @PostMapping("/api/v1/admin/resources/cleanup")
    Map<String, Object> cleanup(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessions.require(auth, "ADMIN");
        return media.cleanup();
    }
}
