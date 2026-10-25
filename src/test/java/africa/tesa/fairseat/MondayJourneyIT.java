package africa.tesa.fairseat;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.fairseat.auth.Role;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Monday definition of done, through HTTP only: sign in, create a 2-seat event, three
 * sign-ups, one cancels, the waiting person is offered the seat and accepts, My Events is true.
 */
class MondayJourneyIT extends IntegrationTestBase {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    @Test
    void organiserCreatesTwoSeatsThreeSignUpOneCancelsWaiterAccepts() {
        String organiser = signIn(user(Role.ORGANISER));
        String a1 = signIn(user(Role.ATTENDEE));
        String a2 = signIn(user(Role.ATTENDEE));
        String a3 = signIn(user(Role.ATTENDEE));

        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        HttpResponse<String> created = post("/events", organiser, """
                {"title":"Journey meetup","category":"COMMUNITY","location":"Lekki, Lagos",
                 "startsAt":"%s","endsAt":"%s","seats":2}
                """.formatted(start, start.plus(2, ChronoUnit.HOURS)));
        assertThat(created.statusCode()).isEqualTo(201);
        UUID eventId = idOf(created);

        HttpResponse<String> s1 = post("/events/" + eventId + "/signups", a1, null);
        HttpResponse<String> s2 = post("/events/" + eventId + "/signups", a2, null);
        HttpResponse<String> s3 = post("/events/" + eventId + "/signups", a3, null);
        assertThat(s1.body()).contains("\"status\":\"GOING\"");
        assertThat(s2.body()).contains("\"status\":\"GOING\"");
        assertThat(s3.body()).contains("\"status\":\"WAITING\"").contains("\"waitingPosition\":1");

        HttpResponse<String> cancelled = post("/signups/" + idOf(s1) + "/cancellation", a1, null);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(cancelled.body()).contains("\"status\":\"CANCELLED\"");

        assertThat(get("/me/signups", a3).body()).contains("\"status\":\"OFFERED\"");
        assertThat(get("/me/notices", a3).body()).contains("SEAT_OFFERED");

        HttpResponse<String> accepted = post("/signups/" + idOf(s3) + "/acceptance", a3, null);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains("\"status\":\"GOING\"");

        assertThat(get("/me/signups", a1).body()).contains("\"status\":\"CANCELLED\"");
        assertThat(get("/me/signups", a2).body()).contains("\"status\":\"GOING\"");
        assertThat(get("/me/signups", a3).body()).contains("\"status\":\"GOING\"");

        HttpResponse<String> event = get("/events/" + eventId, null);
        assertThat(event.body()).contains("\"seatsTaken\":2", "\"waitingCount\":0", "\"seatsAvailable\":0");
    }

    @Test
    void wrongPasswordIsRefused() {
        TestUser u = user(Role.ATTENDEE);
        HttpResponse<String> r = post("/sessions", null,
                "{\"email\":\"" + u.email() + "\",\"password\":\"wrong-password\"}");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    private String signIn(TestUser u) {
        HttpResponse<String> r = post("/sessions", null,
                "{\"email\":\"" + u.email() + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.statusCode()).isEqualTo(201);
        Matcher m = TOKEN.matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
