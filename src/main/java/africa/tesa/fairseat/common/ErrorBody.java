package africa.tesa.fairseat.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.slf4j.MDC;

/** The shared Error shape: {code, message, requestId, details}. details is left out when empty. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message, String requestId, Map<String, Object> details) {

    public static ErrorBody of(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorBody(code.name(), message, currentRequestId(), details);
    }

    public static String currentRequestId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id == null ? "none" : id;
    }

    /**
     * Hand-written JSON for the security handlers, which run outside Spring MVC.
     * Only used for bodies without details.
     */
    public String toJson() {
        return "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
                + "\",\"requestId\":\"" + escape(requestId) + "\"}";
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
