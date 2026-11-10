package campus.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonContractConfiguration {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictRequestTypes() {
        return builder ->
                builder.postConfigurer(
                        mapper -> {
                            mapper.coercionConfigFor(LogicalType.Textual)
                                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                            mapper.coercionConfigFor(LogicalType.Integer)
                                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                                    .setCoercion(
                                            CoercionInputShape.EmptyString, CoercionAction.Fail)
                                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                        });
    }
}
