package africa.tesa.fairseat;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.fairseat.auth.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The rule FairSeat depends on: confirmed seats never exceed the event's seats.
 *
 * To record the failing run for the slides, in a throwaway commit remove
 * {@code AND seats_taken < seats} from SignupRepository.takeSeat and the CHECK
 * events_seats_taken_range from 001-core-schema.sql. This test then reports 10 GOING.
 * (Removing only the WHERE guard also fails it: the CHECK turns 8 requests into 500s.)
 */
class SeatConcurrencyIT extends IntegrationTestBase {

    @Test
    void tenSimultaneousSignUpsForTwoSeatsGiveTwoGoingAndEightWaiting() throws Exception {
        TestUser organiser = user(Role.ORGANISER);
        UUID eventId = event(organiser, 2);
        List<TestUser> attendees = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            attendees.add(user(Role.ATTENDEE));
        }

        List<Integer> codes = fireTogether(attendees.stream()
                .map(a -> (Callable<Integer>) () -> post("/events/" + eventId + "/signups", a.token(), null).statusCode())
                .toList());

        assertThat(codes).as("every request answered 201").containsOnly(201).hasSize(10);
        assertThat(countSignups(eventId, "GOING")).isEqualTo(2);
        assertThat(countSignups(eventId, "WAITING")).isEqualTo(8);
        assertThat(seatsTaken(eventId)).isEqualTo(2);
    }

    @Test
    void theSameSignUpSentTwiceAtOnceRecordsOne() throws Exception {
        TestUser organiser = user(Role.ORGANISER);
        UUID eventId = event(organiser, 5);
        TestUser attendee = user(Role.ATTENDEE);

        List<Integer> codes = fireTogether(List.of(
                () -> post("/events/" + eventId + "/signups", attendee.token(), null).statusCode(),
                () -> post("/events/" + eventId + "/signups", attendee.token(), null).statusCode()));

        assertThat(codes).containsExactlyInAnyOrder(201, 200);
        assertThat(countSignups(eventId, "GOING")).isEqualTo(1);
        assertThat(seatsTaken(eventId)).isEqualTo(1);
    }

    @Test
    void waitingListIsFirstComeFirstServed() {
        TestUser organiser = user(Role.ORGANISER);
        UUID eventId = event(organiser, 1);
        TestUser first = user(Role.ATTENDEE);
        TestUser second = user(Role.ATTENDEE);
        TestUser third = user(Role.ATTENDEE);

        UUID firstSignup = idOf(post("/events/" + eventId + "/signups", first.token(), null));
        UUID secondSignup = idOf(post("/events/" + eventId + "/signups", second.token(), null));
        UUID thirdSignup = idOf(post("/events/" + eventId + "/signups", third.token(), null));

        post("/signups/" + firstSignup + "/cancellation", first.token(), null);

        assertThat(signupStatus(secondSignup)).isEqualTo("OFFERED");
        assertThat(signupStatus(thirdSignup)).isEqualTo("WAITING");
    }

    private static List<Integer> fireTogether(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
