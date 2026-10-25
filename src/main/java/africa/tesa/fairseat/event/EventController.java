package africa.tesa.fairseat.event;

import africa.tesa.fairseat.auth.Caller;
import africa.tesa.fairseat.common.ApiException;
import africa.tesa.fairseat.common.ErrorCode;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventController {

    public record EventPage(List<EventView> items, int total, int limit, int offset, Instant asOf) {
    }

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @GetMapping("/events")
    public EventPage list(@RequestParam(required = false) String q,
                          @RequestParam(required = false) Category category,
                          @RequestParam(required = false) String location,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(required = false) UUID organiserId,
                          @RequestParam(defaultValue = "false") boolean includePast,
                          @RequestParam(defaultValue = "false") boolean includeCancelled,
                          @RequestParam(defaultValue = "20") int limit,
                          @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100) {
            throw badParam("limit", "must be between 1 and 100");
        }
        if (offset < 0) {
            throw badParam("offset", "must be 0 or more");
        }
        if (q != null && q.length() > 100) {
            throw badParam("q", "must be at most 100 characters");
        }
        if (location != null && location.length() > 100) {
            throw badParam("location", "must be at most 100 characters");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw badParam("to", "must be on or after from");
        }
        var page = service.search(new EventRepository.Filter(q, category, location, from, to, organiserId,
                includePast, includeCancelled, limit, offset));
        return new EventPage(page.items(), page.total(), limit, offset, page.asOf());
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public EventView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EventService.EventCreate req) {
        return service.create(Caller.required(jwt), req);
    }

    @GetMapping("/events/{eventId}")
    public EventView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return service.get(eventId, Caller.from(jwt));
    }

    @PatchMapping("/events/{eventId}")
    public EventView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
                            @Valid @RequestBody EventService.EventUpdate req) {
        return service.update(Caller.required(jwt), eventId, req);
    }

    @PostMapping("/events/{eventId}/cancellation")
    public EventView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return service.cancel(Caller.required(jwt), eventId);
    }

    private static ApiException badParam(String name, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "The value of '" + name + "' is not allowed.",
                Map.of("fields", Map.of(name, message)));
    }
}
