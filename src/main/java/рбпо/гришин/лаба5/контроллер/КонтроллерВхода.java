package рбпо.гришин.лаба5.контроллер;

import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import рбпо.гришин.лаба5.модель.ПараТокенов;
import рбпо.гришин.лаба5.модель.Пользователь;
import рбпо.гришин.лаба5.сервис.СервисПользователей;
import рбпо.гришин.лаба5.сервис.СервисСессий;

@RestController
public class КонтроллерВхода {
    private final СервисСессий сессии;
    private final СервисПользователей пользователи;

    public КонтроллерВхода(СервисСессий сессии, СервисПользователей пользователи) {
        this.сессии = сессии;
        this.пользователи = пользователи;
    }

    @PostMapping("/auth/login")
    public ПараТокенов войти(@RequestBody Вход запрос) {
        return сессии.войти(запрос.username, запрос.password, запрос.deviceId);
    }

    @PostMapping("/auth/register")
    public ResponseEntity<ПараТокенов> зарегистрироваться(@RequestBody Регистрация запрос) {
        ПараТокенов пара = сессии.зарегистрировать(запрос.имя, запрос.логин, запрос.пароль, запрос.deviceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(пара);
    }

    @PostMapping("/auth/refresh")
    public ПараТокенов обновить(@RequestBody Обновление запрос) {
        ПараТокенов пара = сессии.обновить(запрос.refreshToken);
        if (пара == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh-токен уже использован и отозван.");
        }
        return пара;
    }

    @GetMapping("/api/auth/me")
    public Пользователь текущийПользователь(Authentication вход) {
        return пользователи.получитьТекущего(вход);
    }

    public static class Вход {
        @JsonAlias("логин")
        public String username;
        @JsonAlias("пароль")
        public String password;
        public String deviceId;
    }

    public static class Регистрация {
        @JsonAlias("name")
        public String имя;
        @JsonAlias("username")
        public String логин;
        @JsonAlias("password")
        public String пароль;
        public String deviceId;
    }

    public static class Обновление {
        public String refreshToken;
    }
}
