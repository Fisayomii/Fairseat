package africa.tesa.fairseat.auth;

import africa.tesa.fairseat.common.ApiException;
import africa.tesa.fairseat.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    public record SessionRequest(@NotBlank @Email @Size(max = 254) String email,
                                 @NotBlank @Size(max = 128) String password) {
    }

    public record UserView(UUID id, String email, String displayName, Role role) {
    }

    public record SessionView(String token, Instant expiresAt, UserView user) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    /** Compared against when the email is unknown, so both failures take the same time. */
    private final String dummyHash;

    public SessionController(UserRepository users, PasswordEncoder passwords, TokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(@Valid @RequestBody SessionRequest req) {
        var user = users.findByEmail(req.email());
        boolean ok = passwords.matches(req.password(), user.map(UserRepository.UserRow::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            log.info("Sign-in refused");
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "That email and password do not match an account.");
        }
        var u = user.get();
        var issued = tokens.issue(u.id(), u.role(), u.displayName());
        log.info("Signed in user={} role={}", u.id(), u.role());
        return new SessionView(issued.token(), issued.expiresAt(),
                new UserView(u.id(), u.email(), u.displayName(), u.role()));
    }
}
