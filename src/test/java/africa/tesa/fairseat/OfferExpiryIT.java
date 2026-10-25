package africa.tesa.fairseat;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.fairseat.auth.Role;
import africa.tesa.fairseat.signup.OfferSweeper;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** An offer past its window reads as expired without any write, and the seat moves on. */
class OfferExpiryIT extends IntegrationTestBase {

    @Autowired
    OfferSweeper sweeper;

    @Test
    void lapsedOfferReadsAsExpiredWithoutWritingThenTheSweeperMovesTheSeatOn() {
        Fixture f = oneSeatWithTwoWaiting();
        lapse(f.secondSignup);

        // A read reports the truth...
        assertThat(get("/me/signups", f.second.token()).body()).contains("\"status\":\"OFFER_EXPIRED\"");
        assertThat(get("/events/" + f.eventId, null).body()).contains("\"offeredCount\":0", "\"seatsAvailable\":1");
        // ...and changes nothing.
        assertThat(signupStatus(f.secondSignup)).isEqualTo("OFFERED");

        // Nobody touches the event; the sweeper passes the seat on.
        sweeper.sweep();

        assertThat(signupStatus(f.secondSignup)).isEqualTo("OFFER_EXPIRED");
        assertThat(signupStatus(f.thirdSignup)).isEqualTo("OFFERED");
        assertThat(seatsTaken(f.eventId)).isEqualTo(1);
    }

    @Test
    void acceptingALapsedOfferIsRefusedAndTheSeatStillMovesOn() {
        Fixture f = oneSeatWithTwoWaiting();
        lapse(f.secondSignup);

        HttpResponse<String> r = post("/signups/" + f.secondSignup + "/acceptance", f.second.token(), null);

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("\"code\":\"OFFER_EXPIRED\"");
        assertThat(signupStatus(f.secondSignup)).isEqualTo("OFFER_EXPIRED");
        assertThat(signupStatus(f.thirdSignup)).isEqualTo("OFFERED");
    }

    @Test
    void acceptingTwiceIsSafe() {
        Fixture f = oneSeatWithTwoWaiting();

        assertThat(post("/signups/" + f.secondSignup + "/acceptance", f.second.token(), null).statusCode()).isEqualTo(200);
        assertThat(post("/signups/" + f.secondSignup + "/acceptance", f.second.token(), null).statusCode()).isEqualTo(200);
        assertThat(signupStatus(f.secondSignup)).isEqualTo("GOING");
        assertThat(seatsTaken(f.eventId)).isEqualTo(1);
    }

    private record Fixture(UUID eventId, TestUser second, UUID secondSignup, UUID thirdSignup) {
    }

    /** 1 seat: first GOING, second and third WAITING; first cancels so second is OFFERED. */
    private Fixture oneSeatWithTwoWaiting() {
        TestUser organiser = user(Role.ORGANISER);
        UUID eventId = event(organiser, 1);
        TestUser first = user(Role.ATTENDEE);
        TestUser second = user(Role.ATTENDEE);
        TestUser third = user(Role.ATTENDEE);
        UUID s1 = idOf(post("/events/" + eventId + "/signups", first.token(), null));
        UUID s2 = idOf(post("/events/" + eventId + "/signups", second.token(), null));
        UUID s3 = idOf(post("/events/" + eventId + "/signups", third.token(), null));
        post("/signups/" + s1 + "/cancellation", first.token(), null);
        assertThat(signupStatus(s2)).isEqualTo("OFFERED");
        return new Fixture(eventId, second, s2, s3);
    }

    private void lapse(UUID signupId) {
        jdbc.sql("UPDATE signups SET offer_expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", signupId).update();
    }
}
