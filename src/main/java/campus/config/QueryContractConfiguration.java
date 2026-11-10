package campus.config;

import static campus.exception.BusinessException.fail;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/** Validate explicit scalar values before Spring substitutes @RequestParam defaults. */
@Configuration
public class QueryContractConfiguration implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                if (!(handler instanceof HandlerMethod method)) return true;
                                for (var parameter : method.getMethodParameters()) {
                                    var annotation =
                                            parameter.getParameterAnnotation(RequestParam.class);
                                    if (annotation == null) continue;
                                    String name =
                                            !annotation.name().isEmpty()
                                                    ? annotation.name()
                                                    : !annotation.value().isEmpty()
                                                            ? annotation.value()
                                                            : parameter.getParameter().getName();
                                    if (!Set.of("page", "size", "order").contains(name)) continue;
                                    String[] values = request.getParameterValues(name);
                                    if (values != null
                                            && (values.length != 1 || values[0].isBlank())) {
                                        throw fail(
                                                400,
                                                "order".equals(name)
                                                        ? "INVALID_ORDER"
                                                        : "INVALID_REQUEST");
                                    }
                                }
                                return true;
                            }
                        })
                .addPathPatterns("/api/v1/**");
    }
}
