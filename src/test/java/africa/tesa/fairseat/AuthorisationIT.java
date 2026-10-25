package africa.tesa.fairseat;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.fairseat.auth.Role;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Calls the service directly with the wrong role or the wrong owner. Each test fails if the
 * matching check in EventService or SignupService is removed: the request would succeed and the
 * database assertion would see the change.
 */
class AuthorisationIT extends IntegrationTestBase {

    private static final String NEW_EVENT = """
            {"title":"Sneaky meetup","category":"TECH","location":"Ikeja, Lagos",
             "startsAt":"2099-01-01T15:00:00Z","endsAt":"2099-01-01T18:00:00Z","seats":10}
            """;

    @Test
    void attendeeCannotCreateAnEvent() {
        TestUser attendee = user(Role.ATTENDEE);

        HttpResponse<String> r = post("/events", attendee.token(), NEW_EVENT);

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("\"code\":\"FORBIDDEN_ROLE\"");
        assertThat(jdbc.sql("SELECT count(*) FROM events WHERE organiser_id = :id")
                .param("id", attendee.id()).query(Integer.class).single()).isZero();
    }

    @Test
    void organiserCannotEditAnotherOrganisersEvent() {
        TestUser owner = user(Role.ORGANISER);
        TestUser other = user(Role.ORGANISER);
        UUID eventId = event(owner, 10);

        HttpResponse<String> r = patch("/events/" + eventId, other.token(), "{\"title\":\"Taken over\"}");

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("\"code\":\"NOT_EVENT_OWNER\"");
        assertThat(jdbc.sql("SELECT title FROM events WHERE id = :id").param("id", eventId)
                .query(String.class).single()).isEqualTo("Test meetup");
    }

    @Test
    void organiserCannotCancelAnotherOrganisersEvent() {
        TestUser owner = user(Role.ORGANISER);
        TestUser other = user(Role.ORGANISER);
        UUID eventId = event(owner, 10);

        HttpResponse<String> r = post("/events/" + eventId + "/cancellation", other.token(), null);

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT status FROM events WHERE id = :id").param("id", eventId)
                .query(String.class).single()).isEqualTo("SCHEDULED");
    }

    @Test
    void ownerAndAdminCanEdit() {
        TestUser owner = user(Role.ORGANISER);
        TestUser admin = user(Role.ADMIN);
        UUID eventId = event(owner, 10);

        assertThat(patch("/events/" + eventId, owner.token(), "{\"title\":\"Renamed\"}").statusCode()).isEqualTo(200);
        assertThat(patch("/events/" + eventId, admin.token(), "{\"seats\":12}").statusCode()).isEqualTo(200);
    }

    @Test
    void attendeeCannotCancelSomeoneElsesSignUp() {
        TestUser organiser = user(Role.ORGANISER);
        UUID eventId = event(organiser, 2);
        TestUser holder = user(Role.ATTENDEE);
        TestUser other = user(Role.ATTENDEE);
        UUID signupId = idOf(post("/events/" + eventId + "/signups", holder.token(), null));

        HttpResponse<String> r = post("/signups/" + signupId + "/cancellation", other.token(), null);

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("\"code\":\"NOT_SIGNUP_OWNER\"");
        assertThat(signupStatus(signupId)).isEqualTo("GOING");
    }

    @Test
    void organiserCannotSignUpAsAnAttendee() {
        TestUser organiser = user(Role.ORGANISER);
        UUID eventId = event(organiser, 2);

        HttpResponse<String> r = post("/events/" + eventId + "/signups", organiser.token(), null);

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(countSignups(eventId, "GOING")).isZero();
    }

    @Test
    void noTokenIsRefusedWithTheErrorShapeAndRequestId() {
        HttpResponse<String> r = post("/events", null, NEW_EVENT);

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(r.headers().firstValue("X-Request-Id")).isPresent();
    }
}
