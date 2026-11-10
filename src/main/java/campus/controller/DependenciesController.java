package campus.controller;

import campus.service.DependencyService;
import campus.service.SessionService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DependenciesController {
    private final SessionService sessions;
    private final DependencyService dependencies;

    public DependenciesController(SessionService sessions, DependencyService dependencies) {
        this.sessions = sessions;
        this.dependencies = dependencies;
    }

    @GetMapping("/api/v1/admin/dependencies")
    public Map<String, Object> health(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessions.require(auth, "ADMIN");
        return dependencies.health();
    }
}
