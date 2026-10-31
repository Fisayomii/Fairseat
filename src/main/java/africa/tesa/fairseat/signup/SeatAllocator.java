package africa.tesa.fairseat.signup;

import africa.tesa.fairseat.event.EventRepository.LockedEvent;
import africa.tesa.fairseat.notice.NoticeRepository;
import africa.tesa.fairseat.notice.NoticeRepository.Kind;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two seat moves shared by every write path and the sweeper. Callers must already hold the
 * event row lock, which is why these require an existing transaction.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class SeatAllocator {

    private static final Logger LOG = LoggerFactory.getLogger(SeatAllocator.class);
    static final DateTimeFormatter LAGOS = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK)
            .withZone(ZoneId.of("Africa/Lagos"));

    private final SignupRepository signups;
    private final NoticeRepository notices;
    private final int offerWindowHours;
    private final int offerMinMinutes;

    public SeatAllocator(SignupRepository signups, NoticeRepository notices,
                         @Value("${fairseat.offers.window-hours:24}") int offerWindowHours,
                         @Value("${fairseat.offers.min-minutes:30}") int offerMinMinutes) {
        this.signups = signups;
        this.notices = notices;
        this.offerWindowHours = offerWindowHours;
        this.offerMinMinutes = offerMinMinutes;
    }

    /** Writes OFFER_EXPIRED for lapsed offers, frees their seats, and passes the seats on. */
    public void applyExpiry(LockedEvent event) {
        List<UUID> lapsed = signups.expireLapsedOffers(event.id());
        if (!lapsed.isEmpty()) {
            signups.releaseSeats(event.id(), lapsed.size());
            notices.addAll(lapsed, event.id(), Kind.OFFER_EXPIRED,
                    "Your seat offer for " + event.title() + " expired, so it went to the next person waiting.");
            LOG.info("Expired {} offer(s) event={}", lapsed.size(), event.id());
        }
        promote(event);
    }

    /** While a seat is free and someone is waiting, offer it to the next person in line. */
    public void promote(LockedEvent event) {
        if (event.cancelled() || event.started()) {
            return;
        }
        while (true) {
            var next = signups.nextWaiting(event.id());
            if (next.isEmpty() || signups.takeSeat(event.id()) == 0) {
                return;
            }
            Instant expires = signups.offer(next.get().id(), offerWindowHours, offerMinMinutes);
            notices.add(next.get().userId(), event.id(), Kind.SEAT_OFFERED,
                    "A seat opened at " + event.title() + ". Accept it before " + LAGOS.format(expires) + " (Lagos time).");
            LOG.info("Offered seat signup={} event={}", next.get().id(), event.id());
        }
    }
}
