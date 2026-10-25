package africa.tesa.fairseat.signup;

import africa.tesa.fairseat.auth.Caller;
import africa.tesa.fairseat.common.ApiException;
import africa.tesa.fairseat.common.ErrorCode;
import africa.tesa.fairseat.notice.NoticeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SignupController {

    public record SignupList(List<SignupView> items, Instant asOf) {
    }

    public record NoticeList(List<NoticeRepository.NoticeView> items) {
    }

    private static final Set<String> SCOPES = Set.of("upcoming", "past", "all");

    private final SignupService service;
    private final NoticeRepository notices;

    public SignupController(SignupService service, NoticeRepository notices) {
        this.service = service;
        this.notices = notices;
    }

    @PostMapping("/events/{eventId}/signups")
    public ResponseEntity<SignupView> signUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        var result = service.signUp(Caller.required(jwt), eventId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.signup());
    }

    @PostMapping("/signups/{signupId}/cancellation")
    public SignupView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID signupId) {
        return service.cancel(Caller.required(jwt), signupId);
    }

    @PostMapping("/signups/{signupId}/acceptance")
    public SignupView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID signupId) {
        return service.accept(Caller.required(jwt), signupId);
    }

    @GetMapping("/me/signups")
    public SignupList mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "upcoming") String scope) {
        if (!SCOPES.contains(scope)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The value of 'scope' is not allowed.",
                    Map.of("fields", Map.of("scope", "must be upcoming, past or all")));
        }
        List<SignupView> items = service.mine(Caller.required(jwt), scope);
        Instant asOf = items.isEmpty() ? Instant.now() : items.getFirst().asOf();
        return new SignupList(items, asOf);
    }

    @GetMapping("/me/notices")
    public NoticeList notices(@AuthenticationPrincipal Jwt jwt) {
        return new NoticeList(notices.latestFor(Caller.required(jwt).id()));
    }
}
