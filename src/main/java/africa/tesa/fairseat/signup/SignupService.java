package africa.tesa.fairseat.signup;

import africa.tesa.fairseat.auth.Caller;
import africa.tesa.fairseat.auth.Role;
import africa.tesa.fairseat.common.ApiException;
import africa.tesa.fairseat.common.ErrorCode;
import africa.tesa.fairseat.event.EventRepository;
import africa.tesa.fairseat.event.EventRepository.LockedEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-up, cancel and accept. Each write locks the event row first, applies any lapsed offers,
 * then makes its change, all in one transaction. Lock order is always event then sign-ups.
 */
@Service
public class SignupService {

    private static final Logger LOG = LoggerFactory.getLogger(SignupService.class);

    /** {@code created} is false when an existing active sign-up was returned unchanged. */
    public record SignUpResult(SignupView signup, boolean created) {
    }

    private record AcceptOutcome(SignupView signup, ErrorCode refusal, String message) {
    }

    private final EventRepository events;
    private final SignupRepository signups;
    private final SeatAllocator allocator;
    private final TransactionTemplate tx;

    public SignupService(EventRepository events, SignupRepository signups, SeatAllocator allocator,
                         TransactionTemplate tx) {
        this.events = events;
        this.signups = signups;
        this.allocator = allocator;
        this.tx = tx;
    }

    public SignUpResult signUp(Caller caller, UUID eventId) {
        caller.requireRole(Role.ATTENDEE);
        try {
            return tx.execute(status -> {
                LockedEvent event = lock(eventId);
                if (event.cancelled()) {
                    throw new ApiException(ErrorCode.EVENT_CANCELLED, "This event has been cancelled.");
                }
                if (event.started()) {
                    throw new ApiException(ErrorCode.EVENT_STARTED, "This event has already started.");
                }
                allocator.applyExpiry(event);
                var existing = signups.findActive(eventId, caller.id());
                if (existing.isPresent()) {
                    return new SignUpResult(view(existing.get().id()), false);
                }
                String newStatus = signups.takeSeat(eventId) == 1 ? "GOING" : "WAITING";
                UUID id = signups.insert(eventId, caller.id(), newStatus);
                LOG.info("Signed up signup={} event={} status={}", id, eventId, newStatus);
                return new SignUpResult(view(id), true);
            });
        } catch (DuplicateKeyException e) {
            // Backstop: the partial unique index caught a second active sign-up. The transaction
            // rolled back, including any seat it took; return the one that won.
            return new SignUpResult(signups.findActiveView(eventId, caller.id()).orElseThrow(() -> e), false);
        }
    }

    public SignupView cancel(Caller caller, UUID signupId) {
        return tx.execute(status -> {
            var row = ownedBy(caller, signupId);
            LockedEvent event = lock(row.eventId());
            allocator.applyExpiry(event);
            var current = signups.find(signupId).orElseThrow();
            switch (current.status()) {
                case "GOING", "OFFERED" -> {
                    signups.setStatus(signupId, "CANCELLED");
                    signups.releaseSeats(event.id(), 1);
                    allocator.promote(event);
                }
                case "WAITING" -> signups.setStatus(signupId, "CANCELLED");
                default -> {
                    // Already CANCELLED or OFFER_EXPIRED: nothing to do.
                }
            }
            LOG.info("Cancel signup={} was={}", signupId, current.status());
            return view(signupId);
        });
    }

    public SignupView accept(Caller caller, UUID signupId) {
        AcceptOutcome outcome = tx.execute(status -> {
            var row = ownedBy(caller, signupId);
            LockedEvent event = lock(row.eventId());
            if (signups.acceptLiveOffer(signupId) == 1) {
                LOG.info("Accepted offer signup={}", signupId);
                return new AcceptOutcome(view(signupId), null, null);
            }
            // Not a live offer. Apply any expiry now, and commit it, so the seat moves on even
            // though this request is refused.
            allocator.applyExpiry(event);
            String now = signups.find(signupId).orElseThrow().status();
            return switch (now) {
                case "GOING" -> new AcceptOutcome(view(signupId), null, null);
                case "OFFER_EXPIRED" -> new AcceptOutcome(null, ErrorCode.OFFER_EXPIRED,
                        "This seat offer has expired and the seat has gone to the next person waiting.");
                default -> new AcceptOutcome(null, ErrorCode.NO_ACTIVE_OFFER,
                        "There is no seat offer to accept for this sign-up.");
            };
        });
        if (outcome.refusal() != null) {
            throw new ApiException(outcome.refusal(), outcome.message());
        }
        return outcome.signup();
    }

    public List<SignupView> mine(Caller caller, String scope) {
        return signups.listForUser(caller.id(), scope);
    }

    private SignupRepository.SignupRow ownedBy(Caller caller, UUID signupId) {
        var row = signups.find(signupId)
                .orElseThrow(() -> new ApiException(ErrorCode.SIGNUP_NOT_FOUND, "That sign-up does not exist."));
        if (!row.userId().equals(caller.id())) {
            throw new ApiException(ErrorCode.NOT_SIGNUP_OWNER, "This sign-up belongs to someone else.");
        }
        return row;
    }

    private LockedEvent lock(UUID eventId) {
        return events.lockForUpdate(eventId)
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND, "That event does not exist."));
    }

    private SignupView view(UUID signupId) {
        return signups.findView(signupId).orElseThrow();
    }
}
