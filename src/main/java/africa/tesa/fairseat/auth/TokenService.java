package africa.tesa.fairseat.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues the self-signed HS256 tokens. Verification is Spring Security's resource server (JwtConfig). */
@Service
public class TokenService {

    public static final String ISSUER = "fairseat";
    public static final String ROLES_CLAIM = "roles";
    public static final String NAME_CLAIM = "name";
    static final Duration LIFETIME = Duration.ofHours(8);

    public record IssuedToken(String token, Instant expiresAt) {
    }

    private final JwtEncoder encoder;

    public TokenService(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    public IssuedToken issue(UUID userId, Role role, String displayName) {
        Instant now = Instant.now();
        Instant expires = now.plus(LIFETIME);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expires)
                .claim(ROLES_CLAIM, List.of(role.name()))
                .claim(NAME_CLAIM, displayName)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expires);
    }
}
