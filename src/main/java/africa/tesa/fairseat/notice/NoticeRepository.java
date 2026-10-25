package africa.tesa.fairseat.notice;

import africa.tesa.fairseat.common.Db;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** In-platform notices: a plain table, written in the same transaction as the change they report. */
@Repository
public class NoticeRepository {

    public enum Kind { SEAT_OFFERED, OFFER_EXPIRED, EVENT_CHANGED, EVENT_CANCELLED }

    public record NoticeView(UUID id, String kind, UUID eventId, String message, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public NoticeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(UUID userId, UUID eventId, Kind kind, String message) {
        jdbc.sql("INSERT INTO notices (user_id, event_id, kind, message) VALUES (:user, :event, :kind, :message)")
                .param("user", userId)
                .param("event", eventId)
                .param("kind", kind.name())
                .param("message", message)
                .update();
    }

    public void addAll(Collection<UUID> userIds, UUID eventId, Kind kind, String message) {
        for (UUID user : userIds) {
            add(user, eventId, kind, message);
        }
    }

    public List<NoticeView> latestFor(UUID userId) {
        return jdbc.sql("""
                        SELECT id, kind, event_id, message, created_at FROM notices
                        WHERE user_id = :user ORDER BY created_at DESC, id LIMIT 50
                        """)
                .param("user", userId)
                .query((rs, n) -> new NoticeView(Db.uuid(rs, "id"), rs.getString("kind"),
                        Db.uuid(rs, "event_id"), rs.getString("message"), Db.instant(rs, "created_at")))
                .list();
    }
}
