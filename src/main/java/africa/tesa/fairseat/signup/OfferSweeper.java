package africa.tesa.fairseat.signup;

import africa.tesa.fairseat.event.EventRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves seats on from lapsed offers when nobody touches the event. Reads already report a
 * lapsed offer as OFFER_EXPIRED; this makes it true in the data and offers the seat onwards.
 *
 * If this does not run for an hour (free host asleep): reads still show the truth, and the next
 * sign-up, cancel or accept on that event applies the same expiry before doing anything else.
 * The only cost is that the next person waiting gets their offer later.
 */
@Component
public class OfferSweeper {

    private static final Logger log = LoggerFactory.getLogger(OfferSweeper.class);

    private final SignupRepository signups;
    private final EventRepository events;
    private final SeatAllocator allocator;
    private final TransactionTemplate tx;

    public OfferSweeper(SignupRepository signups, EventRepository events, SeatAllocator allocator,
                        TransactionTemplate tx) {
        this.signups = signups;
        this.events = events;
        this.allocator = allocator;
        this.tx = tx;
    }

    /** Returns how many events were processed. Public so tests can run it directly. */
    public int sweep() {
        int done = 0;
        for (UUID eventId : signups.eventsWithLapsedOffers(100)) {
            try {
                tx.executeWithoutResult(s -> events.lockForUpdate(eventId).ifPresent(allocator::applyExpiry));
                done++;
            } catch (RuntimeException e) {
                log.error("Offer sweep failed event={}", eventId, e);
            }
        }
        if (done > 0) {
            log.info("Offer sweep processed {} event(s)", done);
        }
        return done;
    }

    /** The timer. Off in tests (fairseat.offers.sweep-enabled=false) so they control timing. */
    @Component
    @ConditionalOnProperty(name = "fairseat.offers.sweep-enabled", havingValue = "true", matchIfMissing = true)
    static class Job {

        private final OfferSweeper sweeper;

        Job(OfferSweeper sweeper) {
            this.sweeper = sweeper;
        }

        @Scheduled(initialDelayString = "PT15S", fixedDelayString = "${fairseat.offers.sweep-interval:PT1M}")
        void run() {
            sweeper.sweep();
        }
    }
}
