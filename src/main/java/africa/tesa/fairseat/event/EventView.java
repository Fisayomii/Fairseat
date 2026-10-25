package africa.tesa.fairseat.event;

import africa.tesa.fairseat.signup.SignupView;
import java.time.Instant;
import java.util.UUID;

/** Matches Event in the contract. Counts are computed from sign-ups at {@code asOf}. */
public record EventView(
        UUID id,
        String title,
        String description,
        Category category,
        String location,
        Instant startsAt,
        Instant endsAt,
        int seats,
        int seatsTaken,
        int seatsAvailable,
        int goingCount,
        int offeredCount,
        int waitingCount,
        String status,
        Organiser organiser,
        SignupView mySignup,
        Instant asOf) {

    public record Organiser(UUID id, String displayName) {
    }

    public EventView withMySignup(SignupView signup) {
        return new EventView(id, title, description, category, location, startsAt, endsAt, seats, seatsTaken,
                seatsAvailable, goingCount, offeredCount, waitingCount, status, organiser, signup, asOf);
    }
}
