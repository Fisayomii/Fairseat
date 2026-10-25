package africa.tesa.fairseat;

import africa.tesa.fairseat.auth.Role;
import africa.tesa.fairseat.auth.TokenService;
import africa.tesa.fairseat.common.Db;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real Postgres in a container, real HTTP on a random port. One container for every test class
 * so the Spring context is shared. The offer sweeper's timer is off so tests control timing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fairseat.offers.sweep-enabled=false")
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    protected static final String PASSWORD = "test-password-1";
    private static final Pattern FIRST_ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TokenService tokens;

    @Autowired
    protected PasswordEncoder passwords;

    protected final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // ---- data helpers -------------------------------------------------------------------------

    protected record TestUser(UUID id, String email, Role role, String token) {
    }

    protected TestUser user(Role role) {
        String email = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.fairseat";
        UUID id = jdbc.sql("""
                        INSERT INTO users (email, display_name, password_hash, role)
                        VALUES (:email, :name, :hash, :role) RETURNING id
                        """)
                .param("email", email)
                .param("name", "Test " + role.name().toLowerCase())
                .param("hash", passwords.encode(PASSWORD))
                .param("role", role.name())
                .query(UUID.class)
                .single();
        return new TestUser(id, email, role, tokens.issue(id, role, "Test").token());
    }

    /** An event a week from now, inserted directly. */
    protected UUID event(TestUser organiser, int seats) {
        Instant start = Instant.now().plus(7, ChronoUnit.DAYS);
        return jdbc.sql("""
                        INSERT INTO events (organiser_id, title, category, location, starts_at, ends_at, seats)
                        VALUES (:org, 'Test meetup', 'TECH', 'Yaba, Lagos', :start, :end, :seats) RETURNING id
                        """)
                .param("org", organiser.id())
                .param("start", Db.ts(start))
                .param("end", Db.ts(start.plus(3, ChronoUnit.HOURS)))
                .param("seats", seats)
                .query(UUID.class)
                .single();
    }

    protected int countSignups(UUID eventId, String status) {
        return jdbc.sql("SELECT count(*) FROM signups WHERE event_id = :e AND status = :s")
                .param("e", eventId)
                .param("s", status)
                .query(Integer.class)
                .single();
    }

    protected String signupStatus(UUID signupId) {
        return jdbc.sql("SELECT status FROM signups WHERE id = :id").param("id", signupId)
                .query(String.class).single();
    }

    protected int seatsTaken(UUID eventId) {
        return jdbc.sql("SELECT seats_taken FROM events WHERE id = :id").param("id", eventId)
                .query(Integer.class).single();
    }

    // ---- HTTP helpers -------------------------------------------------------------------------

    protected HttpResponse<String> get(String path, String token) {
        return send(request(path, token).GET());
    }

    protected HttpResponse<String> post(String path, String token, String json) {
        return send(request(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json)));
    }

    protected HttpResponse<String> patch(String path, String token, String json) {
        return send(request(path, token).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    /** The first "id" in a JSON body: the top-level resource id for Event and Signup. */
    protected static UUID idOf(HttpResponse<String> response) {
        Matcher m = FIRST_ID.matcher(response.body());
        if (!m.find()) {
            throw new AssertionError("No id in: " + response.body());
        }
        return UUID.fromString(m.group(1));
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Request-Id", "test-" + UUID.randomUUID());
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private HttpResponse<String> send(HttpRequest.Builder b) {
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
