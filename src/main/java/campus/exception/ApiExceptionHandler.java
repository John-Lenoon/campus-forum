package campus.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException e) {
        return ResponseEntity.status(e.status())
                .body(
                        e.code().equals("COMMENT_DELETED")
                                ? Map.of("code", e.code(), "message", "该评论已删除")
                                : Map.of("code", e.code()));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        ServletRequestBindingException.class,
        MissingServletRequestPartException.class,
        MultipartException.class
    })
    ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }

    @ExceptionHandler({DataAccessException.class, RedisConnectionFailureException.class})
    ResponseEntity<?> unavailable(Exception e) {
        return ResponseEntity.status(503).body(Map.of("code", "DEPENDENCY_UNAVAILABLE"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> uploadTooLarge(Exception e) {
        return ResponseEntity.status(413).body(Map.of("code", "UPLOAD_TOO_LARGE"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> notFound(Exception e) {
        return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        var methods = e.getSupportedHttpMethods();
        return ResponseEntity.status(405)
                .allow(methods == null ? new HttpMethod[0] : methods.toArray(HttpMethod[]::new))
                .body(Map.of("code", "METHOD_NOT_ALLOWED"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> unsupportedMedia(Exception e) {
        return ResponseEntity.status(415).body(Map.of("code", "UNSUPPORTED_MEDIA_TYPE"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        int status = e instanceof ErrorResponse response ? response.getStatusCode().value() : 500;
        if (status >= 500)
            org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class)
                    .error("Unhandled HTTP failure", e);
        return ResponseEntity.status(status)
                .body(Map.of("code", status >= 500 ? "INTERNAL_ERROR" : "INVALID_REQUEST"));
    }
}
