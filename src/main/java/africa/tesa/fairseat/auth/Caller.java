package africa.tesa.fairseat.auth;

import africa.tesa.fairseat.common.ApiException;
import africa.tesa.fairseat.common.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Who is calling, read from the verified JWT. Role checks live in the services, not the interface. */
public record Caller(UUID id, Role role, String name) {

    public static Optional<Caller> from(Jwt jwt) {
        if (jwt == null) {
            return Optional.empty();
        }
        List<String> roles = jwt.getClaimAsStringList(TokenService.ROLES_CLAIM);
        if (roles == null || roles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Caller(UUID.fromString(jwt.getSubject()), Role.valueOf(roles.getFirst()),
                jwt.getClaimAsString(TokenService.NAME_CLAIM)));
    }

    public static Caller required(Jwt jwt) {
        return from(jwt).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in to continue."));
    }

    public void requireRole(Role... allowed) {
        for (Role r : allowed) {
            if (r == role) {
                return;
            }
        }
        throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Your account type cannot do this.");
    }
}
