package africa.tesa.fairseat.common;

import org.springframework.http.HttpStatus;

/** Stable error codes. Must match the Error.code enum in contract/openapi.yaml. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN),
    NOT_EVENT_OWNER(HttpStatus.FORBIDDEN),
    NOT_SIGNUP_OWNER(HttpStatus.FORBIDDEN),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    SIGNUP_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    EVENT_CANCELLED(HttpStatus.CONFLICT),
    EVENT_STARTED(HttpStatus.CONFLICT),
    SEATS_BELOW_TAKEN(HttpStatus.CONFLICT),
    OFFER_EXPIRED(HttpStatus.CONFLICT),
    NO_ACTIVE_OFFER(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
