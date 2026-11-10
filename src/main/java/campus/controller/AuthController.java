package campus.controller;

import campus.dto.LoginRequest;
import campus.dto.RegisterRequest;
import campus.service.AccountService;
import campus.service.SessionService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AccountService accounts;
    private final SessionService sessions;

    public AuthController(AccountService accounts, SessionService sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@RequestBody RegisterRequest input) {
        return accounts.register(input.username(), input.password(), input.nickname()).view();
    }

    @PostMapping("/auth/login")
    Map<String, Object> login(@RequestBody LoginRequest input) {
        return sessions.login(input.username(), input.password());
    }
}
