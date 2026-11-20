package рбпо.гришин.лаба5.сервис;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import рбпо.гришин.лаба5.безопасность.СервисJwt;
import рбпо.гришин.лаба5.модель.SessionStatus;
import рбпо.гришин.лаба5.модель.UserSession;
import рбпо.гришин.лаба5.модель.ПараТокенов;
import рбпо.гришин.лаба5.модель.Пользователь;
import рбпо.гришин.лаба5.репозиторий.РепозиторийСессий;
import рбпо.гришин.лаба5.репозиторий.РепозиторийТеатра;

import java.time.Instant;

@Service
@Transactional
public class СервисСессий {
    private final РепозиторийСессий сессии;
    private final РепозиторийТеатра театр;
    private final СервисПользователей пользователи;
    private final СервисJwt jwt;
    private final DaoAuthenticationProvider проверкаПароля;

    public СервисСессий(РепозиторийСессий сессии, РепозиторийТеатра театр,
                       СервисПользователей пользователи, СервисJwt jwt,
                       DaoAuthenticationProvider проверкаПароля) {
        this.сессии = сессии;
        this.театр = театр;
        this.пользователи = пользователи;
        this.jwt = jwt;
        this.проверкаПароля = проверкаПароля;
    }

    public ПараТокенов войти(String логин, String пароль, String устройство) {
        проверитьПоле(логин, "логин");
        проверитьПоле(пароль, "пароль");
        устройство = проверитьУстройство(устройство);
        театр.заблокироватьИзменения();
        String имя;
        try {
            имя = проверкаПароля.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(логин, пароль)).getName();
        } catch (AuthenticationException ошибка) {
            throw отказ("Неверные учётные данные.");
        }
        return выдать(театр.найтиПользователяПоЛогину(имя), устройство);
    }

    public ПараТокенов зарегистрировать(String имя, String логин, String пароль, String устройство) {
        устройство = проверитьУстройство(устройство);
        Пользователь пользователь = пользователи.зарегистрировать(имя, логин, пароль);
        // Пользователя и сессию сохраняю одной транзакцией.
        return выдать(пользователь, устройство);
    }

    public ПараТокенов обновить(String refresh) {
        проверитьПоле(refresh, "refreshToken");
        DecodedJWT токен;
        try {
            токен = jwt.проверитьRefresh(refresh);
        } catch (JWTVerificationException ошибка) {
            throw отказ("Refresh-токен неверен или просрочен.");
        }
        // Блокирую параллельное изменение пользователя при обмене.
        театр.заблокироватьИзменения();
        UserSession прежняя = сессии.найтиСБлокировкой(refresh);
        if (прежняя == null || !токен.getSubject().equals(прежняя.username)
                || !прежняя.refreshTokenExpiry.isAfter(Instant.now())
                || !прежняя.refreshTokenExpiry.equals(токен.getExpiresAtAsInstant())) {
            throw отказ("Refresh-сессия недоступна.");
        }
        if (прежняя.status == SessionStatus.USED) {
            сессии.изменитьСтатус(прежняя.id, SessionStatus.REVOKED);
            // Сначала сохраняю REVOKED, затем контроллер возвращает 401.
            return null;
        }
        if (прежняя.status != SessionStatus.ACTIVE) {
            throw отказ("Refresh-сессия недоступна.");
        }
        Пользователь пользователь = театр.найтиПользователяПоЛогину(прежняя.username);
        if (пользователь == null || !Boolean.TRUE.equals(пользователь.активен)) {
            throw отказ("Refresh-сессия недоступна.");
        }
        сессии.изменитьСтатус(прежняя.id, SessionStatus.USED);
        // При ошибке записи откатывается и изменение статуса.
        return выдать(пользователь, прежняя.deviceId);
    }

    private ПараТокенов выдать(Пользователь пользователь, String устройство) {
        if (пользователь == null || !Boolean.TRUE.equals(пользователь.активен)) {
            throw отказ("Пользователь недоступен.");
        }
        UserSession новая = new UserSession();
        новая.username = пользователь.логин;
        новая.deviceId = устройство;
        новая.accessToken = jwt.создатьAccess(пользователь.логин);
        новая.refreshToken = jwt.создатьRefresh(пользователь.логин);
        новая.accessTokenExpiry = jwt.проверитьAccess(новая.accessToken).getExpiresAtAsInstant();
        новая.refreshTokenExpiry = jwt.проверитьRefresh(новая.refreshToken).getExpiresAtAsInstant();
        новая.status = SessionStatus.ACTIVE;
        сессии.сохранить(новая);
        return new ПараТокенов(новая.accessToken, новая.refreshToken);
    }

    private void проверитьПоле(String значение, String поле) {
        if (значение == null || значение.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите поле " + поле + ".");
        }
    }

    private String проверитьУстройство(String устройство) {
        if (устройство == null || устройство.isBlank()) {
            return null;
        }
        if (устройство.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId должен быть не длиннее 200 символов.");
        }
        return устройство;
    }

    private ResponseStatusException отказ(String сообщение) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, сообщение);
    }
}
