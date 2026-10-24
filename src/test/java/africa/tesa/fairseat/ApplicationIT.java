package africa.tesa.fairseat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the whole service against a real Postgres (not a substitute) and checks the two things
 * the v0 pipeline depends on: migrations run and the app is healthy, and closed paths refuse strangers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    // Property rather than @LocalServerPort, whose package moved between Boot versions.
    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void healthIsUpAfterMigrations() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void protectedPathWithoutTokenIsRefusedWith401() throws Exception {
        HttpResponse<String> response = get("/me/signups");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void preflightFromTheInterfaceIsAllowed() throws Exception {
        HttpResponse<String> response = preflight("http://localhost:5173");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
    }

    @Test
    void preflightFromAnUnknownSiteIsRefused() throws Exception {
        HttpResponse<String> response = preflight("https://not-our-site.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sessions"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,x-request-id")
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
