package campus.config;

import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class VideoConfiguration {
    @Bean
    public Queue videoQueue(
            @Value("${spring.datasource.url}") String url,
            @Value("${forum.video-queue:}") String configured) {
        return new Queue(
                configured.isBlank()
                        ? "campus-forum.video." + Integer.toUnsignedString(url.hashCode())
                        : configured,
                true,
                false,
                false);
    }
}
