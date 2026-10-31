package africa.tesa.fairseat;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.fairseat.auth.Role;
import africa.tesa.fairseat.common.Db;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The offer window shrinks as the event approaches (docs/adr/0001-offer-window.md): it ends one
 * hour before the start, capped at 24 hours, but always lasts at least 30 minutes and never runs
 * past the start.
 */
class OfferWindowIT extends IntegrationTestBase {

    @Test
    void eventThreeHoursAwayOfferEndsOneHourBeforeStart() {
        Duration left = offerLengthForEventStartingIn(Duration.ofHours(3));
        assertThat(left).isBetween(Duration.ofMinutes(119), Duration.ofMinutes(121));
    }

    @Test
    void eventFortyMinutesAwayStillGetsThirtyMinutes() {
        Duration left = offerLengthForEventStartingIn(Duration.ofMinutes(40));
        assertThat(left).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
    }

    @Test
    void eventTenMinutesAwayOfferEndsAtTheStart() {
        Duration left = offerLengthForEventStartingIn(Duration.ofMinutes(10));
        assertThat(left).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(11));
    }

    @Test
    void eventAWeekAwayKeepsTheFull24Hours() {
        Duration left = offerLengthForEventStartingIn(Duration.ofDays(7));
        assertThat(left).isBetween(Duration.ofMinutes(1439), Duration.ofMinutes(1441));
    }

    /** One seat: first goes, second waits, first cancels; returns how long the second's offer lasts. */
    private Duration offerLengthForEventStartingIn(Duration untilStart) {
        TestUser organiser = user(Role.ORGANISER);
        Instant start = Instant.now().plus(untilStart);
        UUID eventId = jdbc.sql("""
                        INSERT INTO events (organiser_id, title, category, location, starts_at, ends_at, seats)
                        VALUES (:org, 'Window test', 'TECH', 'Yaba, Lagos', :start, :end, 1) RETURNING id
                        """)
                .param("org", organiser.id())
                .param("start", Db.ts(start))
                .param("end", Db.ts(start.plus(2, ChronoUnit.HOURS)))
                .query(UUID.class)
                .single();
        TestUser first = user(Role.ATTENDEE);
        TestUser second = user(Role.ATTENDEE);
        UUID s1 = idOf(post("/events/" + eventId + "/signups", first.token(), null));
        UUID s2 = idOf(post("/events/" + eventId + "/signups", second.token(), null));
        post("/signups/" + s1 + "/cancellation", first.token(), null);

        assertThat(signupStatus(s2)).isEqualTo("OFFERED");
        long seconds = jdbc.sql("SELECT extract(epoch FROM offer_expires_at - now())::bigint FROM signups WHERE id = :id")
                .param("id", s2)
                .query(Long.class)
                .single();
        return Duration.ofSeconds(seconds);
    }
}
