package africa.tesa.fairseat.event;

import africa.tesa.fairseat.auth.Caller;
import africa.tesa.fairseat.auth.Role;
import africa.tesa.fairseat.common.ApiException;
import africa.tesa.fairseat.common.ErrorCode;
import africa.tesa.fairseat.event.EventRepository.LockedEvent;
import africa.tesa.fairseat.image.CloudinaryConfig;
import africa.tesa.fairseat.image.ImageSigner;
import africa.tesa.fairseat.image.ImageStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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

    /** A signed upload: post {@code fields} plus the file, as multipart form data, to {@code uploadUrl}. */
    public record ImageUploadTicket(String uploadUrl, String publicId, Instant expiresAt, long maxBytes,
                                    List<String> allowedFormats, Map<String, String> fields) {
    }

    public record ImageAttach(@NotBlank @Size(max = 255) String publicId) {
    }

    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final List<String> IMAGE_FORMATS = List.of("jpg", "png", "webp");
    private static final Set<String> STORED_FORMATS = Set.of("jpg", "jpeg", "png", "webp");
    /** Cloudinary accepts a signature for one hour. */
    private static final long TICKET_SECONDS = 3600;
    /** Stored images are scaled down to fit 1600 x 1600 on the way in, never up. */
    private static final String INCOMING_TRANSFORMATION = "c_limit,w_1600,h_1600";

    private final EventRepository events;
    private final SignupRepository signups;
    private final SeatAllocator allocator;
    private final NoticeRepository notices;
    private final TransactionTemplate tx;
    private final CloudinaryConfig cloudinary;
    private final ImageStore images;

    public EventService(EventRepository events, SignupRepository signups, SeatAllocator allocator,
                        NoticeRepository notices, TransactionTemplate tx, CloudinaryConfig cloudinary,
                        ImageStore images) {
        this.events = events;
        this.signups = signups;
        this.allocator = allocator;
        this.notices = notices;
        this.tx = tx;
        this.cloudinary = cloudinary;
        this.images = images;
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

    // ---- event image (ADR 0003) -----------------------------------------------------------------

    /**
     * Signs one direct upload to Cloudinary for this event. The browser can only upload to the
     * public id signed here, in the allowed formats, within the hour.
     */
    public ImageUploadTicket imageUploadTicket(Caller caller, UUID id) {
        caller.requireRole(Role.ORGANISER, Role.ADMIN);
        requireImagesConfigured();
        tx.executeWithoutResult(status -> requireEditable(lockOwned(caller, id)));
        long timestamp = Instant.now().getEpochSecond();
        String publicId = "fairseat/events/" + id + "/cover-" + timestamp;

        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("timestamp", Long.toString(timestamp));
        signed.put("public_id", publicId);
        signed.put("allowed_formats", String.join(",", IMAGE_FORMATS));
        signed.put("transformation", INCOMING_TRANSFORMATION);
        if (!cloudinary.uploadPreset().isEmpty()) {
            signed.put("upload_preset", cloudinary.uploadPreset());
        }
        Map<String, String> fields = new LinkedHashMap<>(signed);
        fields.put("api_key", cloudinary.apiKey());
        fields.put("signature", ImageSigner.sign(signed, cloudinary.apiSecret()));

        LOG.info("Image upload ticket event={} by={}", id, caller.id());
        return new ImageUploadTicket("https://api.cloudinary.com/v1_1/" + cloudinary.cloudName() + "/image/upload",
                publicId, Instant.ofEpochSecond(timestamp + TICKET_SECONDS), MAX_IMAGE_BYTES, IMAGE_FORMATS,
                Map.copyOf(fields));
    }

    /**
     * Sets the event's image to one already uploaded. The service asks Cloudinary itself whether
     * the image exists and at which version, so a browser cannot point an event at someone else's
     * image or at one that was never uploaded.
     */
    public EventView attachImage(Caller caller, UUID id, ImageAttach req) {
        caller.requireRole(Role.ORGANISER, Role.ADMIN);
        requireImagesConfigured();
        tx.executeWithoutResult(status -> requireEditable(lockOwned(caller, id)));
        Pattern own = Pattern.compile("^fairseat/events/" + Pattern.quote(id.toString()) + "/cover-\\d{1,12}$");
        if (!own.matcher(req.publicId()).matches()) {
            throw new ApiException(ErrorCode.IMAGE_NOT_ALLOWED,
                    "That image was not uploaded for this event. Request a new upload and try again.");
        }
        var stored = images.find(req.publicId()).orElseThrow(() -> new ApiException(ErrorCode.IMAGE_NOT_UPLOADED,
                "The image has not finished uploading. Upload it, then try again."));
        if (!STORED_FORMATS.contains(stored.format()) || stored.bytes() > MAX_IMAGE_BYTES) {
            throw new ApiException(ErrorCode.IMAGE_NOT_ALLOWED, "Use a JPG, PNG or WebP image of 5 MB or less.");
        }
        tx.executeWithoutResult(status -> {
            requireEditable(lockOwned(caller, id));
            events.setImage(id, stored.publicId(), stored.version());
        });
        LOG.info("Image set event={} publicId={} version={}", id, stored.publicId(), stored.version());
        return events.findView(id).orElseThrow();
    }

    /** Removes the event's image. The file stays in Cloudinary (see README limitations). */
    public EventView removeImage(Caller caller, UUID id) {
        caller.requireRole(Role.ORGANISER, Role.ADMIN);
        tx.executeWithoutResult(status -> {
            requireEditable(lockOwned(caller, id));
            events.setImage(id, null, null);
        });
        LOG.info("Image removed event={} by={}", id, caller.id());
        return events.findView(id).orElseThrow();
    }

    private void requireImagesConfigured() {
        if (!cloudinary.configured()) {
            throw new ApiException(ErrorCode.IMAGES_NOT_CONFIGURED, "Event images are not available right now.");
        }
    }

    private static void requireEditable(LockedEvent event) {
        if (event.cancelled()) {
            throw new ApiException(ErrorCode.EVENT_CANCELLED, "A cancelled event cannot be edited.");
        }
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
