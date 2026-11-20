package рбпо.гришин.лаба5.репозиторий;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import рбпо.гришин.лаба5.модель.SessionStatus;
import рбпо.гришин.лаба5.модель.UserSession;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Repository
public class РепозиторийСессий {
    private final JdbcTemplate база;

    public РепозиторийСессий(JdbcTemplate база) {
        this.база = база;
    }

    public UserSession сохранить(UserSession сессия) {
        if (сессия.id == null) {
            сессия.id = UUID.randomUUID();
        }
        // Сохраняю сроки с точностью JWT до секунды.
        сессия.accessTokenExpiry = сессия.accessTokenExpiry.truncatedTo(ChronoUnit.SECONDS);
        сессия.refreshTokenExpiry = сессия.refreshTokenExpiry.truncatedTo(ChronoUnit.SECONDS);
        база.update("INSERT INTO user_sessions (id, username, device_id, access_token, refresh_token, "
                        + "access_token_expiry, refresh_token_expiry, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                сессия.id, сессия.username, сессия.deviceId, сессия.accessToken, сессия.refreshToken,
                сессия.accessTokenExpiry.atOffset(ZoneOffset.UTC),
                сессия.refreshTokenExpiry.atOffset(ZoneOffset.UTC), сессия.status.name());
        return сессия;
    }

    public UserSession найтиСБлокировкой(String refreshToken) {
        // Второй запрос ждёт завершения первого.
        List<UserSession> сессии = база.query("SELECT * FROM user_sessions WHERE refresh_token = ? FOR UPDATE",
                this::прочитатьСессию, refreshToken);
        return сессии.isEmpty() ? null : сессии.get(0);
    }

    public void изменитьСтатус(UUID id, SessionStatus статус) {
        база.update("UPDATE user_sessions SET status = ? WHERE id = ?", статус.name(), id);
    }

    private UserSession прочитатьСессию(ResultSet строка, int номерСтроки) throws SQLException {
        UserSession сессия = new UserSession();
        сессия.id = строка.getObject("id", UUID.class);
        сессия.username = строка.getString("username");
        сессия.deviceId = строка.getString("device_id");
        сессия.accessToken = строка.getString("access_token");
        сессия.refreshToken = строка.getString("refresh_token");
        сессия.accessTokenExpiry = строка.getObject("access_token_expiry", OffsetDateTime.class).toInstant();
        сессия.refreshTokenExpiry = строка.getObject("refresh_token_expiry", OffsetDateTime.class).toInstant();
        сессия.status = SessionStatus.valueOf(строка.getString("status"));
        return сессия;
    }
}
