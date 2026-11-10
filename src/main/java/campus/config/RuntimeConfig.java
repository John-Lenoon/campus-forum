package campus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class RuntimeConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
