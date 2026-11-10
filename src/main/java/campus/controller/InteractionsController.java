package campus.controller;

import campus.service.InteractionService;
import campus.service.SessionService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class InteractionsController {
    private final SessionService sessions;
    private final InteractionService interactions;

    InteractionsController(SessionService sessions, InteractionService interactions) {
        this.sessions = sessions;
        this.interactions = interactions;
    }

    @RequestMapping(
            path = "/posts/{id}/{kind}",
            method = {RequestMethod.PUT, RequestMethod.DELETE})
    Map<String, Object> change(
            @PathVariable String id,
            @PathVariable String kind,
            @RequestHeader(value = "Authorization", required = false) String auth,
            HttpServletRequest request) {
        return interactions.change(
                id, sessions.require(auth, "USER"), kind, "PUT".equals(request.getMethod()));
    }

    @GetMapping({"/me/likes", "/me/favorites"})
    Map<String, Object> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        String kind = request.getRequestURI().endsWith("/likes") ? "likes" : "favorites";
        return interactions.list(sessions.require(auth, "USER"), kind, page, size);
    }
}
