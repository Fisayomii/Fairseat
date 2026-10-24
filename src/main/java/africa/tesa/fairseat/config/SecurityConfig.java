package africa.tesa.fairseat.config;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * v0 security: stateless, everything closed except health and sign-in.
 * JWT validation, roles and the Error-shaped 401/403 bodies arrive with the auth work (Fri 23 Oct).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // A token-based API with no cookies, so there is no CSRF exposure to protect against.
            // The interface is a separate site on its own address, so the browser needs CORS (see corsConfigurationSource).
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/sessions").permitAll()
                .requestMatchers(HttpMethod.GET, "/events", "/events/*").permitAll()
                .anyRequest().authenticated())
            // Unauthenticated calls get 401, not Spring's default 403, as the contract says.
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * Allows only the interface's own addresses. Set ALLOWED_ORIGINS on the host to the deployed site,
     * comma-separated, e.g. https://bloodlink.netlify.app. Defaults cover the Vite dev server (5173) and vite preview (4173).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        // Headers the interface must be able to read from responses.
        cors.setExposedHeaders(List.of("X-Request-Id", "Idempotent-Replayed", "Location"));
        // Bearer tokens, no cookies.
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
