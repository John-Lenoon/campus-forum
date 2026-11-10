package campus.controller;

import campus.dto.CommentRequest;
import campus.service.CommentService;
import campus.service.SessionService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CommentsController {
    private final SessionService sessions;
    private final CommentService comments;

    CommentsController(SessionService sessions, CommentService comments) {
        this.sessions = sessions;
        this.comments = comments;
    }

    @PostMapping("/posts/{id}/comments")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> create(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody CommentRequest input) {
        return comments.create(id, null, sessions.require(auth, "USER"), input.body());
    }

    @PostMapping("/comments/{id}/replies")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> reply(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody CommentRequest input) {
        return comments.create(null, id, sessions.require(auth, "USER"), input.body());
    }

    @GetMapping("/comments/{id}")
    Map<String, Object> detail(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return comments.detail(id, sessions.read(auth, false));
    }

    @DeleteMapping("/comments/{id}")
    Map<String, Object> delete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return comments.delete(id, sessions.read(auth, true));
    }

    @GetMapping("/posts/{id}/comments")
    Map<String, Object> roots(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "asc") String order) {
        return comments.list(id, null, sessions.read(auth, false), page, size, order);
    }

    @GetMapping("/comments/{id}/replies")
    Map<String, Object> replies(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "asc") String order) {
        return comments.list(null, id, sessions.read(auth, false), page, size, order);
    }

    @GetMapping("/me/comments")
    Map<String, Object> history(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return comments.history(sessions.require(auth, "USER"), page, size);
    }
}
