package africa.tesa.fairseat.signup;

import java.time.Instant;
import java.util.UUID;

/** Matches Signup in the contract. {@code status} is the effective status at {@code asOf}. */
public record SignupView(
        UUID id,
        EventSummary event,
        String status,
        Integer waitingPosition,
        Instant offerExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant asOf) {

    public record EventSummary(UUID id, String title, String location, Instant startsAt, Instant endsAt,
                               String status) {
    }
}
