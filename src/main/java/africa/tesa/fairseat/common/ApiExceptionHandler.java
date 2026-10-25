package africa.tesa.fairseat.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        LOG.info("Refused: {} {}", e.code(), e.getMessage());
        return respond(e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return respond(ErrorCode.VALIDATION_FAILED, "Some fields need attention.", Map.of("fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return respond(ErrorCode.VALIDATION_FAILED,
                "The request body is not valid JSON for this operation, or a value is not allowed.", null);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ErrorBody> badParameter(Exception e) {
        String name = e instanceof MethodArgumentTypeMismatchException m ? m.getName()
                : ((MissingServletRequestParameterException) e).getParameterName();
        return respond(ErrorCode.VALIDATION_FAILED, "The value of '" + name + "' is not allowed.",
                Map.of("fields", Map.of(name, "not allowed")));
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ErrorBody> noRoute(Exception e) {
        return respond(ErrorCode.NOT_FOUND, "There is nothing at this address.", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        LOG.error("Unhandled error", e);
        return respond(ErrorCode.INTERNAL_ERROR,
                "Something went wrong on our side. Try again; if it keeps happening, quote the request id.", null);
    }

    private static ResponseEntity<ErrorBody> respond(ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(code.status()).body(ErrorBody.of(code, message, details));
    }
}
