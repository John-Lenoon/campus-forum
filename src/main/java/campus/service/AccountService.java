package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.CurrentUser;
import campus.entity.UserEntity;
import campus.exception.BusinessException;
import campus.mapper.UserMapper;
import campus.support.AvatarUrls;
import campus.support.TextValidation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService {
    private final UserMapper users;
    private final Clock clock;
    private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder("", 16, 600000, 256);

    public AccountService(UserMapper users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    public CurrentUser register(String username, String password, String nickname) {
        if (username == null || !username.matches("[A-Za-z0-9_]{4,32}"))
            throw fail(400, "INVALID_USERNAME");
        TextValidation.text(password, 8, 64, "INVALID_PASSWORD");
        if (nickname == null) nickname = username;
        TextValidation.text(nickname, 1, 32, "INVALID_NICKNAME");
        return insert(username, password, nickname, "USER");
    }

    private CurrentUser insert(String username, String password, String nickname, String role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setUsernameKey(username.toLowerCase(Locale.ROOT));
        user.setPasswordHash(encoder.encode(password));
        user.setNickname(nickname);
        user.setRole(role);
        user.setCreatedAt(clock.millis());
        try {
            users.insert(user);
        } catch (DuplicateKeyException e) {
            throw fail(409, "USERNAME_EXISTS");
        }
        return principal(user);
    }

    public CurrentUser verify(String username, String password) {
        if (username == null || password == null || password.length() > 256)
            throw fail(401, "INVALID_CREDENTIALS");
        var rows = users.findByUsername(username.toLowerCase(Locale.ROOT));
        if (rows.isEmpty() || !encoder.matches(password, rows.getFirst().getPasswordHash()))
            throw fail(401, "INVALID_CREDENTIALS");
        return principal(rows.getFirst());
    }

    public CurrentUser byId(String id) {
        var user = users.selectById(id);
        if (user == null) throw fail(401, "INVALID_CREDENTIALS");
        return principal(user);
    }

    private CurrentUser principal(UserEntity user) {
        return new CurrentUser(
                user.getId(),
                user.getNickname(),
                user.getRole(),
                AvatarUrls.forResource(user.getAvatarResourceId()));
    }

    public void initializeAdmin() {
        if (users.findByUsername("admin").isEmpty()) {
            try {
                insert("admin", "admin123", "admin", "ADMIN");
            } catch (BusinessException e) {
                if (e.status() != 409) throw e;
            }
        }
        if (!"ADMIN".equals(users.findByUsername("admin").getFirst().getRole())) {
            throw new IllegalStateException("Reserved admin account has an invalid role");
        }
    }
}
