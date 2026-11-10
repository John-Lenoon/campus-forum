package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.CurrentUser;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Service
public class SessionService {
    private final StringRedisTemplate redis;
    private final AccountService accounts;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SessionService(StringRedisTemplate redis, AccountService accounts, Clock clock) {
        this.redis = redis;
        this.accounts = accounts;
        this.clock = clock;
    }

    public Map<String, Object> login(String username, String password) {
        var user = accounts.verify(username, password);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        long expiry = clock.millis() + 86_400_000L;
        redis.opsForValue().set(key(token), user.id() + ":" + expiry, Duration.ofHours(24));
        return Map.of("token", token, "expiresAt", expiry, "user", user.view());
    }

    public CurrentUser read(String header, boolean required) {
        if (header == null) {
            if (required) throw fail(401, "AUTH_REQUIRED");
            return null;
        }
        if (!header.matches("Bearer [0-9a-f]{64}")) throw fail(401, "INVALID_TOKEN");
        String value = redis.opsForValue().get(key(header.substring(7)));
        if (value == null) throw fail(401, "INVALID_TOKEN");
        var parts = value.split(":");
        if (parts.length != 2 || clock.millis() >= Long.parseLong(parts[1]))
            throw fail(401, "TOKEN_EXPIRED");
        return accounts.byId(parts[0]);
    }

    public CurrentUser require(String header, String role) {
        var user = read(header, true);
        if (!role.equals(user.role())) throw fail(403, "ROLE_FORBIDDEN");
        return user;
    }

    private String key(String token) {
        try {
            return "campus-forum:session:"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
