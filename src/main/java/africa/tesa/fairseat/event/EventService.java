package africa.tesa.fairseat.event;

import africa.tesa.fairseat.auth.Caller;
import africa.tesa.fairseat.auth.Role;
import africa.tesa.fairseat.common.ApiException;
import africa.tesa.fairseat.common.ErrorCode;
import africa.tesa.fairseat.event.EventRepository.LockedEvent;
import africa.tesa.fairseat.notice.NoticeRepository;
import africa.tesa.fairseat.notice.NoticeRepository.Kind;
import africa.tesa.fairseat.signup.SeatAllocator;
import africa.tesa.fairseat.signup.SignupRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventService {

    private static final Logger LOG = LoggerFactory.getLogger(EventService.class);

    public record EventCreate(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotNull Category category,
            @NotBlank @Size(max = 200) String location,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull @Min(1) @Max(1000) Integer seats) {
    }

    public record EventUpdate(
            @Size(min = 1, max = 200) String title,
            @Size(max = 4000) String description,
            Category category,
            @Size(min = 1, max = 200) String location,
            Instant startsAt,
            Instant endsAt,
            @Min(1) @Max(1000) Integer seats) {

        boolean isEmpty() {
            return title == null && description == null && category == null && location == null
                    && startsAt == null && endsAt == null && seats == null;
        }
    }

    private final EventRepository events;
    private final SignupRepository signups;
    private final SeatAllocator allocator;
    private final NoticeRepository notices;
    private final TransactionTemplate tx;

    public EventService(EventRepository events, SignupRepository signups, SeatAllocator allocator,
                        NoticeRepository notices, TransactionTemplate tx) {
        this.events = events;
        this.signups = signups;
        this.allocator = allocator;
        this.notices = notices;
        this.tx = tx;
    }

    public EventView create(Caller caller, EventCreate req) {
        caller.requireRole(Role.ORGANISER, Role.ADMIN);
        checkTimes(req.startsAt(), req.endsAt());
        if (!req.startsAt().isAfter(Instant.now())) {
            throw invalid("startsAt", "must be in the future");
        }
        UUID id = events.insert(caller.id(), req.title().trim(), Objects.requireNonNullElse(req.description(), "").trim(),
                req.category(), req.location().trim(), req.startsAt(), req.endsAt(), req.seats());
        LOG.info("Created event={} organiser={}", id, caller.id());
        return events.findView(id).orElseThrow();
    }

    public EventView get(UUID id, Optional<Caller> caller) {
        EventView view = events.findView(id)
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND, "That event does not exist."));
        return caller.map(c -> view.withMySignup(signups.findActiveView(id, c.id()).orElse(null))).orElse(view);
    }

    public EventRepository.Page search(EventRepository.Filter filter) {
        return events.search(filter);
    }

    public EventView update(Caller caller, UUID id, EventUpdate req) {
        caller.requireRole(Role.ORGANISER, Role.ADMIN);
        if (req.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send at least one field to change.");
        }
        tx.executeWithoutResult(status -> {
            LockedEvent event = lockOwned(caller, id);
            if (event.cancelled()) {
                throw new ApiException(ErrorCode.EVENT_CANCELLED, "A cancelled event cannot be edited.");
            }
            var cur = events.editable(id);
            String title = req.title() != null ? req.title().trim() : cur.title();
            String description = req.description() != null ? req.description().trim() : cur.description();
            Category category = req.category() != null ? req.category() : cur.category();
            String location = req.location() != null ? req.location().trim() : cur.location();
            Instant startsAt = req.startsAt() != null ? req.startsAt() : cur.startsAt();
            Instant endsAt = req.endsAt() != null ? req.endsAt() : cur.endsAt();
            if (title.isEmpty()) {
                throw invalid("title", "must not be blank");
            }
            if (location.isEmpty()) {
                throw invalid("location", "must not be blank");
            }
            checkTimes(startsAt, endsAt);

            // Count only live holds before judging a seat reduction.
            allocator.applyExpiry(event);
            LockedEvent fresh = events.lockForUpdate(id).orElseThrow();
            int seats = req.seats() != null ? req.seats() : fresh.seats();
            if (seats < fresh.seatsTaken()) {
                throw new ApiException(ErrorCode.SEATS_BELOW_TAKEN,
                        fresh.seatsTaken() + " seats are already taken or offered, so seats cannot go below that.",
                        Map.of("seatsTaken", fresh.seatsTaken()));
            }
            events.update(id, title, description, category, location, startsAt, endsAt, seats);
            if (seats > fresh.seats()) {
                allocator.promote(events.lockForUpdate(id).orElseThrow());
            }
            boolean changedForAttendees = !title.equals(cur.title()) || !location.equals(cur.location())
                    || !startsAt.equals(cur.startsAt()) || !endsAt.equals(cur.endsAt());
            if (changedForAttendees) {
                List<UUID> holders = signups.activeHolders(id);
                notices.addAll(holders, id, Kind.EVENT_CHANGED,
                        title + " has changed. Check the new time and place.");
            }
            LOG.info("Updated event={} by={}", id, caller.id());
        });
        return events.findView(id).orElseThrow();
    }

    public EventView cancel(Caller caller, UUID id) {
        caller.requireRole(Role.ORGANISER, Role.ADMIN);
        tx.executeWithoutResult(status -> {
            LockedEvent event = lockOwned(caller, id);
            if (event.cancelled()) {
                return;
            }
            List<UUID> holders = signups.cancelAllActive(id);
            events.markCancelled(id);
            notices.addAll(holders, id, Kind.EVENT_CANCELLED, event.title() + " has been cancelled by the organiser.");
            LOG.info("Cancelled event={} by={} notified={}", id, caller.id(), holders.size());
        });
        return events.findView(id).orElseThrow();
    }

    /** Organisers manage only their own events. Admins manage any. Checked here, not in the interface. */
    private LockedEvent lockOwned(Caller caller, UUID id) {
        LockedEvent event = events.lockForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND, "That event does not exist."));
        if (caller.role() != Role.ADMIN && !event.organiserId().equals(caller.id())) {
            throw new ApiException(ErrorCode.NOT_EVENT_OWNER, "Only this event's organiser can change it.");
        }
        return event;
    }

    private static void checkTimes(Instant startsAt, Instant endsAt) {
        if (!endsAt.isAfter(startsAt)) {
            throw invalid("endsAt", "must be after startsAt");
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Some fields need attention.",
                Map.of("fields", Map.of(field, message)));
    }
}
