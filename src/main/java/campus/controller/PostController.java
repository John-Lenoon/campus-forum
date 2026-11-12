package campus.controller;

import campus.dto.EditPostRequest;
import campus.dto.ReviewRequest;
import campus.dto.SubmissionRequest;
import campus.service.PostService;
import campus.service.SessionService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PostController {
    private final SessionService sessions;
    private final PostService posts;

    public PostController(SessionService sessions, PostService posts) {
        this.sessions = sessions;
        this.posts = posts;
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody SubmissionRequest input) {
        return posts.create(
                sessions.require(auth, "USER"), input.title(), input.body(), input.resourceIds());
    }

    @GetMapping("/posts/{id}")
    Map<String, Object> detail(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return posts.detail(id, sessions.read(auth, false));
    }

    @PutMapping("/posts/{id}")
    Map<String, Object> edit(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody EditPostRequest input) {
        return posts.edit(
                id,
                sessions.require(auth, "USER"),
                input.revision(),
                input.title(),
                input.body(),
                input.resourceIds());
    }

    @DeleteMapping("/posts/{id}")
    Map<String, Object> delete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return posts.delete(id, sessions.read(auth, true));
    }

    @GetMapping("/posts")
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return posts.list(false, null, page, size);
    }

    @GetMapping("/admin/posts")
    Map<String, Object> queue(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        sessions.require(auth, "ADMIN");
        return posts.list(true, status, page, size);
    }

    @GetMapping("/me/posts")
    Map<String, Object> mine(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return posts.listMine(sessions.require(auth, "USER"), page, size);
    }

    @PostMapping("/admin/posts/{id}/review")
    Map<String, Object> review(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody ReviewRequest input) {
        return posts.review(
                id,
                sessions.require(auth, "ADMIN"),
                input.revision(),
                input.decision(),
                input.reason());
    }
}
