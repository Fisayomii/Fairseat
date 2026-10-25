package africa.tesa.fairseat.event;

import africa.tesa.fairseat.common.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    /** The event row as locked at the start of a write. {@code dbNow} is the transaction's now(). */
    public record LockedEvent(UUID id, UUID organiserId, String title, String status, Instant startsAt,
                              Instant endsAt, int seats, int seatsTaken, Instant dbNow) {

        public boolean cancelled() {
            return "CANCELLED".equals(status);
        }

        public boolean started() {
            return !startsAt.isAfter(dbNow);
        }
    }

    public record Filter(String q, Category category, String location, LocalDate from, LocalDate to,
                         UUID organiserId, boolean includePast, boolean includeCancelled, int limit, int offset) {
    }

    public record Page(List<EventView> items, int total, Instant asOf) {
    }

    /**
     * Counts come from the sign-ups, not from events.seats_taken, so an offer whose window has
     * passed is not counted even before the sweeper has written OFFER_EXPIRED. A read never writes.
     */
    private static final String VIEW_SELECT = """
            SELECT e.id, e.title, e.description, e.category, e.location, e.starts_at, e.ends_at, e.seats,
                   e.status, e.organiser_id, u.display_name AS organiser_name, now() AS as_of,
                   (SELECT count(*) FROM signups s WHERE s.event_id = e.id AND s.status = 'GOING') AS going,
                   (SELECT count(*) FROM signups s WHERE s.event_id = e.id AND s.status = 'OFFERED'
                        AND s.offer_expires_at > now()) AS offered,
                   (SELECT count(*) FROM signups s WHERE s.event_id = e.id AND s.status = 'WAITING') AS waiting
            """;

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID organiserId, String title, String description, Category category, String location,
                       Instant startsAt, Instant endsAt, int seats) {
        return jdbc.sql("""
                        INSERT INTO events (organiser_id, title, description, category, location, starts_at, ends_at, seats)
                        VALUES (:organiser, :title, :description, :category, :location, :startsAt, :endsAt, :seats)
                        RETURNING id
                        """)
                .param("organiser", organiserId)
                .param("title", title)
                .param("description", description)
                .param("category", category.name())
                .param("location", location)
                .param("startsAt", Db.ts(startsAt))
                .param("endsAt", Db.ts(endsAt))
                .param("seats", seats)
                .query(UUID.class)
                .single();
    }

    /** Locks the event row. Every write that touches seats or sign-ups takes this lock first. */
    public Optional<LockedEvent> lockForUpdate(UUID id) {
        return jdbc.sql("""
                        SELECT id, organiser_id, title, status, starts_at, ends_at, seats, seats_taken, now() AS db_now
                        FROM events WHERE id = :id FOR UPDATE
                        """)
                .param("id", id)
                .query((rs, n) -> new LockedEvent(Db.uuid(rs, "id"), Db.uuid(rs, "organiser_id"),
                        rs.getString("title"), rs.getString("status"), Db.instant(rs, "starts_at"),
                        Db.instant(rs, "ends_at"), rs.getInt("seats"), rs.getInt("seats_taken"),
                        Db.instant(rs, "db_now")))
                .optional();
    }

    public void update(UUID id, String title, String description, Category category, String location,
                       Instant startsAt, Instant endsAt, int seats) {
        jdbc.sql("""
                        UPDATE events SET title = :title, description = :description, category = :category,
                               location = :location, starts_at = :startsAt, ends_at = :endsAt, seats = :seats,
                               updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("title", title)
                .param("description", description)
                .param("category", category.name())
                .param("location", location)
                .param("startsAt", Db.ts(startsAt))
                .param("endsAt", Db.ts(endsAt))
                .param("seats", seats)
                .update();
    }

    public void markCancelled(UUID id) {
        jdbc.sql("UPDATE events SET status = 'CANCELLED', seats_taken = 0, updated_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    /** Current values, for merging a partial update. */
    public record Editable(String title, String description, Category category, String location,
                           Instant startsAt, Instant endsAt) {
    }

    public Editable editable(UUID id) {
        return jdbc.sql("SELECT title, description, category, location, starts_at, ends_at FROM events WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Editable(rs.getString("title"), rs.getString("description"),
                        Category.valueOf(rs.getString("category")), rs.getString("location"),
                        Db.instant(rs, "starts_at"), Db.instant(rs, "ends_at")))
                .single();
    }

    public Optional<EventView> findView(UUID id) {
        return jdbc.sql(VIEW_SELECT + " FROM events e JOIN users u ON u.id = e.organiser_id WHERE e.id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Page search(Filter f) {
        StringBuilder where = new StringBuilder(" WHERE true");
        Map<String, Object> params = new HashMap<>();
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (e.title ILIKE :q ESCAPE '\\' OR e.description ILIKE :q ESCAPE '\\')");
            params.put("q", "%" + likeEscape(f.q().trim()) + "%");
        }
        if (f.category() != null) {
            where.append(" AND e.category = :category");
            params.put("category", f.category().name());
        }
        if (f.location() != null && !f.location().isBlank()) {
            where.append(" AND e.location ILIKE :location ESCAPE '\\'");
            params.put("location", "%" + likeEscape(f.location().trim()) + "%");
        }
        if (f.from() != null) {
            where.append(" AND e.starts_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'Africa/Lagos')");
            params.put("from", f.from());
        }
        if (f.to() != null) {
            where.append(" AND e.starts_at < ((CAST(:to AS date) + 1)::timestamp AT TIME ZONE 'Africa/Lagos')");
            params.put("to", f.to());
        }
        if (f.organiserId() != null) {
            where.append(" AND e.organiser_id = :organiser");
            params.put("organiser", f.organiserId());
        }
        if (!f.includePast()) {
            where.append(" AND e.ends_at > now()");
        }
        if (!f.includeCancelled()) {
            where.append(" AND e.status = 'SCHEDULED'");
        }
        params.put("limit", f.limit());
        params.put("offset", f.offset());

        String sql = VIEW_SELECT + ", count(*) OVER () AS total"
                + " FROM events e JOIN users u ON u.id = e.organiser_id" + where
                + " ORDER BY e.starts_at, e.id LIMIT :limit OFFSET :offset";
        List<EventView> items = new ArrayList<>();
        int[] total = {0};
        jdbc.sql(sql).params(params).query(rs -> {
            items.add(map(rs));
            total[0] = rs.getInt("total");
        });
        Instant asOf = items.isEmpty()
                ? jdbc.sql("SELECT now()").query((rs, n) -> Db.instant(rs, "now")).single()
                : items.getFirst().asOf();
        if (items.isEmpty() && f.offset() > 0) {
            // Past the last page: report the real total so the interface can step back.
            total[0] = jdbc.sql("SELECT count(*) FROM events e" + where).params(params).query(Integer.class).single();
        }
        return new Page(items, total[0], asOf);
    }

    private static EventView map(ResultSet rs) throws SQLException {
        int seats = rs.getInt("seats");
        int going = rs.getInt("going");
        int offered = rs.getInt("offered");
        int taken = going + offered;
        return new EventView(Db.uuid(rs, "id"), rs.getString("title"), rs.getString("description"),
                Category.valueOf(rs.getString("category")), rs.getString("location"),
                Db.instant(rs, "starts_at"), Db.instant(rs, "ends_at"), seats, taken, Math.max(0, seats - taken),
                going, offered, rs.getInt("waiting"), rs.getString("status"),
                new EventView.Organiser(Db.uuid(rs, "organiser_id"), rs.getString("organiser_name")),
                null, Db.instant(rs, "as_of"));
    }

    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
