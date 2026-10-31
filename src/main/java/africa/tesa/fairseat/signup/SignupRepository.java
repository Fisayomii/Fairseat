package africa.tesa.fairseat.signup;

import africa.tesa.fairseat.common.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL that moves seats. The seat rule lives in two places in the database: the guarded
 * UPDATE in {@link #takeSeat} and the CHECK events_seats_taken_range. Every caller holds the
 * event row lock (EventRepository.lockForUpdate) before calling the write methods here.
 */
@Repository
public class SignupRepository {

    public record SignupRow(UUID id, UUID eventId, UUID userId, String status) {
    }

    public record Waiter(UUID id, UUID userId) {
    }

    /** Effective status: an OFFERED row past its window reads as OFFER_EXPIRED, without writing. */
    private static final String VIEW_SELECT = """
            SELECT s.id, s.offer_expires_at, s.created_at, s.updated_at, now() AS as_of,
                   CASE WHEN s.status = 'OFFERED' AND s.offer_expires_at <= now() THEN 'OFFER_EXPIRED'
                        ELSE s.status END AS effective_status,
                   CASE WHEN s.status = 'WAITING' THEN
                        (SELECT count(*) FROM signups w
                         WHERE w.event_id = s.event_id AND w.status = 'WAITING'
                           AND (w.created_at, w.seq) <= (s.created_at, s.seq))
                   END AS waiting_position,
                   e.id AS event_id, e.title, e.location, e.starts_at, e.ends_at, e.status AS event_status
            FROM signups s JOIN events e ON e.id = s.event_id
            """;

    private final JdbcClient jdbc;

    public SignupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- seat counter -------------------------------------------------------------------------

    /**
     * THE PROTECTION. Takes one seat only if one is free. Under concurrent calls Postgres
     * serialises the row update and re-checks the WHERE clause, so at most {@code seats} succeed.
     * Returns 1 if a seat was taken, 0 if the event is full.
     */
    public int takeSeat(UUID eventId) {
        return jdbc.sql("""
                        UPDATE events SET seats_taken = seats_taken + 1, updated_at = now()
                        WHERE id = :id AND seats_taken < seats
                        """)
                .param("id", eventId)
                .update();
    }

    public void releaseSeats(UUID eventId, int count) {
        if (count <= 0) {
            return;
        }
        jdbc.sql("UPDATE events SET seats_taken = seats_taken - :n, updated_at = now() WHERE id = :id")
                .param("id", eventId)
                .param("n", count)
                .update();
    }

    // ---- sign-up rows -------------------------------------------------------------------------

    public UUID insert(UUID eventId, UUID userId, String status) {
        return jdbc.sql("""
                        INSERT INTO signups (event_id, user_id, status) VALUES (:event, :user, :status)
                        RETURNING id
                        """)
                .param("event", eventId)
                .param("user", userId)
                .param("status", status)
                .query(UUID.class)
                .single();
    }

    public Optional<SignupRow> find(UUID id) {
        return jdbc.sql("SELECT id, event_id, user_id, status FROM signups WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    public Optional<SignupRow> findActive(UUID eventId, UUID userId) {
        return jdbc.sql("""
                        SELECT id, event_id, user_id, status FROM signups
                        WHERE event_id = :event AND user_id = :user AND status IN ('GOING', 'WAITING', 'OFFERED')
                        """)
                .param("event", eventId)
                .param("user", userId)
                .query((rs, n) -> row(rs))
                .optional();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE signups SET status = :status, updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("status", status)
                .update();
    }

    /** Accept succeeds only while the offer is live. now() is fixed for the transaction, so the expiry instant has one outcome. */
    public int acceptLiveOffer(UUID id) {
        return jdbc.sql("""
                        UPDATE signups SET status = 'GOING', updated_at = now()
                        WHERE id = :id AND status = 'OFFERED' AND offer_expires_at > now()
                        """)
                .param("id", id)
                .update();
    }

    /** Marks every lapsed offer for the event and returns the holders. */
    public List<UUID> expireLapsedOffers(UUID eventId) {
        return jdbc.sql("""
                        UPDATE signups SET status = 'OFFER_EXPIRED', updated_at = now()
                        WHERE event_id = :event AND status = 'OFFERED' AND offer_expires_at <= now()
                        RETURNING user_id
                        """)
                .param("event", eventId)
                .query(UUID.class)
                .list();
    }

    /** First come, first served: earliest sign-up, then the identity sequence as tie-break. */
    public Optional<Waiter> nextWaiting(UUID eventId) {
        return jdbc.sql("""
                        SELECT id, user_id FROM signups
                        WHERE event_id = :event AND status = 'WAITING'
                        ORDER BY created_at, seq
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("event", eventId)
                .query((rs, n) -> new Waiter(Db.uuid(rs, "id"), Db.uuid(rs, "user_id")))
                .optional();
    }

    /**
     * Offers the seat and returns when the offer ends (see docs/adr/0001-offer-window.md):
     * until 1 hour before the event starts, capped at {@code windowHours}; but always at least
     * {@code minMinutes}, and never past the start. now() is the transaction's time.
     */
    public Instant offer(UUID signupId, int windowHours, int minMinutes) {
        return jdbc.sql("""
                        UPDATE signups s SET status = 'OFFERED', updated_at = now(),
                               offer_expires_at = GREATEST(
                                   LEAST(now() + make_interval(hours => :hours), e.starts_at - interval '1 hour'),
                                   LEAST(now() + make_interval(mins => :mins), e.starts_at))
                        FROM events e
                        WHERE s.id = :id AND e.id = s.event_id
                        RETURNING s.offer_expires_at
                        """)
                .param("id", signupId)
                .param("hours", windowHours)
                .param("mins", minMinutes)
                .query((rs, n) -> Db.instant(rs, "offer_expires_at"))
                .single();
    }

    /** For event cancellation: every active sign-up becomes CANCELLED. Returns the holders. */
    public List<UUID> cancelAllActive(UUID eventId) {
        return jdbc.sql("""
                        UPDATE signups SET status = 'CANCELLED', updated_at = now()
                        WHERE event_id = :event AND status IN ('GOING', 'WAITING', 'OFFERED')
                        RETURNING user_id
                        """)
                .param("event", eventId)
                .query(UUID.class)
                .list();
    }

    public List<UUID> activeHolders(UUID eventId) {
        return jdbc.sql("""
                        SELECT user_id FROM signups
                        WHERE event_id = :event AND status IN ('GOING', 'WAITING', 'OFFERED')
                        """)
                .param("event", eventId)
                .query(UUID.class)
                .list();
    }

    /** Events holding at least one lapsed offer, for the sweeper. */
    public List<UUID> eventsWithLapsedOffers(int max) {
        return jdbc.sql("""
                        SELECT DISTINCT event_id FROM signups
                        WHERE status = 'OFFERED' AND offer_expires_at <= now()
                        LIMIT :max
                        """)
                .param("max", max)
                .query(UUID.class)
                .list();
    }

    // ---- reads (never write) ------------------------------------------------------------------

    public Optional<SignupView> findView(UUID id) {
        return jdbc.sql(VIEW_SELECT + " WHERE s.id = :id")
                .param("id", id)
                .query((rs, n) -> view(rs))
                .optional();
    }

    public Optional<SignupView> findActiveView(UUID eventId, UUID userId) {
        return jdbc.sql(VIEW_SELECT + """
                         WHERE s.event_id = :event AND s.user_id = :user
                           AND s.status IN ('GOING', 'WAITING', 'OFFERED')
                        """)
                .param("event", eventId)
                .param("user", userId)
                .query((rs, n) -> view(rs))
                .optional();
    }

    /** My Events: the latest sign-up per event, so a cancel-then-rejoin shows once with its true status. */
    public List<SignupView> listForUser(UUID userId, String scope) {
        String scopeFilter = switch (scope) {
            case "past" -> " AND e.ends_at <= now()";
            case "all" -> "";
            default -> " AND e.ends_at > now()";
        };
        String order = "upcoming".equals(scope) ? "starts_at ASC" : "starts_at DESC";
        String sql = "SELECT * FROM ("
                + VIEW_SELECT.replaceFirst("SELECT ", "SELECT DISTINCT ON (s.event_id) ")
                + " WHERE s.user_id = :user" + scopeFilter
                + " ORDER BY s.event_id, s.created_at DESC, s.seq DESC) latest"
                + " ORDER BY " + order;
        return jdbc.sql(sql)
                .param("user", userId)
                .query((rs, n) -> view(rs))
                .list();
    }

    private static SignupRow row(ResultSet rs) throws SQLException {
        return new SignupRow(Db.uuid(rs, "id"), Db.uuid(rs, "event_id"), Db.uuid(rs, "user_id"),
                rs.getString("status"));
    }

    private static SignupView view(ResultSet rs) throws SQLException {
        long position = rs.getLong("waiting_position");
        Integer waitingPosition = rs.wasNull() ? null : (int) position;
        return new SignupView(Db.uuid(rs, "id"),
                new SignupView.EventSummary(Db.uuid(rs, "event_id"), rs.getString("title"),
                        rs.getString("location"), Db.instant(rs, "starts_at"), Db.instant(rs, "ends_at"),
                        rs.getString("event_status")),
                rs.getString("effective_status"), waitingPosition, Db.instant(rs, "offer_expires_at"),
                Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"), Db.instant(rs, "as_of"));
    }
}
