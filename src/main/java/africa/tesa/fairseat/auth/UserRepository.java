package africa.tesa.fairseat.auth;

import africa.tesa.fairseat.common.Db;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    public record UserRow(UUID id, String email, String displayName, String passwordHash, Role role) {
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByEmail(String email) {
        return jdbc.sql("""
                        SELECT id, email, display_name, password_hash, role
                        FROM users WHERE lower(email) = lower(:email)
                        """)
                .param("email", email)
                .query((rs, n) -> new UserRow(Db.uuid(rs, "id"), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("password_hash"),
                        Role.valueOf(rs.getString("role"))))
                .optional();
    }
}
