package campus.service;

import static campus.exception.BusinessException.fail;

import campus.mapper.SystemMapper;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DependencyService {
    private final SystemMapper systemMapper;
    private final StringRedisTemplate redis;
    private final ConnectionFactory rabbit;

    public DependencyService(
            SystemMapper systemMapper, StringRedisTemplate redis, ConnectionFactory rabbit) {
        this.systemMapper = systemMapper;
        this.redis = redis;
        this.rabbit = rabbit;
    }

    public Map<String, Object> health() {
        try (var connection = redis.getConnectionFactory().getConnection();
                var mq = rabbit.createConnection()) {
            String version = systemMapper.databaseVersion();
            String ping = connection.ping();
            if (!mq.isOpen() || !"PONG".equals(ping)) throw fail(503, "DEPENDENCY_UNAVAILABLE");
            return Map.of("databaseVersion", version, "redis", ping, "rabbitmq", "UP");
        } catch (RuntimeException e) {
            throw fail(503, "DEPENDENCY_UNAVAILABLE");
        }
    }
}
