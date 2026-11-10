package campus.controller;

import campus.dto.ProfileRequest;
import campus.dto.UserBatchRequest;
import campus.service.SessionService;
import campus.service.UserService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class UsersController {
    private final SessionService sessions;
    private final UserService users;

    public UsersController(SessionService sessions, UserService users) {
        this.sessions = sessions;
        this.users = users;
    }

    @GetMapping("/me")
    public Map<String, Object> me(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return sessions.require(auth, "USER").view();
    }

    @PatchMapping("/me")
    public Map<String, Object> update(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody ProfileRequest input) {
        return users.update(sessions.require(auth, "USER"), input);
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> user(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessions.read(auth, true);
        return users.publicUser(id);
    }

    @PostMapping("/users/batch")
    public Map<String, Object> batch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody UserBatchRequest input) {
        sessions.read(auth, true);
        return users.batch(input.ids());
    }
}
