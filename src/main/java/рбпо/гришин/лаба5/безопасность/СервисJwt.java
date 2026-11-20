package рбпо.гришин.лаба5.безопасность;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
public class СервисJwt {
    private final Algorithm алгоритм;
    private final JWTVerifier проверкаAccess;
    private final JWTVerifier проверкаRefresh;
    private final long срокAccess;
    private final long срокRefresh;

    public СервисJwt(@Value("${JWT_SECRET:}") String секрет,
                     @Value("${JWT_ACCESS_SECONDS:900}") long срокAccess,
                     @Value("${JWT_REFRESH_SECONDS:604800}") long срокRefresh) {
        if (секрет == null || секрет.isBlank()) {
            throw new IllegalStateException("Задайте JWT_SECRET: случайный ключ не короче 32 байт в Base64.");
        }
        byte[] ключ;
        try {
            ключ = Base64.getDecoder().decode(секрет);
        } catch (IllegalArgumentException ошибка) {
            throw new IllegalStateException("JWT_SECRET должен содержать случайный ключ в Base64.");
        }
        if (ключ.length < 32) {
            throw new IllegalStateException("JWT_SECRET должен содержать не менее 32 случайных байт в Base64.");
        }
        if (срокAccess <= 0 || срокRefresh <= 0) {
            throw new IllegalStateException("Сроки JWT_ACCESS_SECONDS и JWT_REFRESH_SECONDS должны быть положительными.");
        }
        this.срокAccess = срокAccess;
        this.срокRefresh = срокRefresh;
        алгоритм = Algorithm.HMAC256(ключ);
        проверкаAccess = создатьПроверку("access");
        проверкаRefresh = создатьПроверку("refresh");
    }

    public String создатьAccess(String логин) {
        return создать(логин, "access", срокAccess);
    }

    public String создатьRefresh(String логин) {
        return создать(логин, "refresh", срокRefresh);
    }

    public DecodedJWT проверитьAccess(String токен) {
        return проверить(токен, проверкаAccess);
    }

    public DecodedJWT проверитьRefresh(String токен) {
        return проверить(токен, проверкаRefresh);
    }

    private String создать(String логин, String тип, long срок) {
        if (логин == null || логин.isBlank()) {
            throw new IllegalArgumentException("Для создания JWT нужен логин.");
        }
        Instant сейчас = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return JWT.create()
                .withSubject(логин)
                .withIssuedAt(сейчас)
                .withExpiresAt(сейчас.plusSeconds(срок))
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("type", тип)
                .sign(алгоритм);
    }

    private JWTVerifier создатьПроверку(String тип) {
        // Разрешаю только HS256.
        return JWT.require(алгоритм)
                .withClaim("type", тип)
                .withClaimPresence("sub")
                .withClaimPresence("iat")
                .withClaimPresence("exp")
                .withClaimPresence("jti")
                .build();
    }

    private DecodedJWT проверить(String токен, JWTVerifier проверка) {
        if (токен == null || токен.isBlank()) {
            throw new JWTVerificationException("Неверный токен.");
        }
        try {
            DecodedJWT данные = проверка.verify(токен);
            String логин = данные.getClaim("sub").asString();
            String номер = данные.getClaim("jti").asString();
            if (логин == null || логин.isBlank() || номер == null || номер.isBlank()) {
                throw new JWTVerificationException("Неверный токен.");
            }
            long выдан = прочитатьСекунды(данные, "iat");
            long истекает = прочитатьСекунды(данные, "exp");
            if (истекает <= выдан) {
                throw new JWTVerificationException("Неверный токен.");
            }
            return данные;
        } catch (IllegalArgumentException | DateTimeException ошибка) {
            throw new JWTVerificationException("Неверный токен.");
        }
    }

    private long прочитатьСекунды(DecodedJWT данные, String поле) {
        JsonNode значение = данные.getClaim(поле).as(JsonNode.class);
        if (значение == null || !значение.isIntegralNumber() || !значение.canConvertToLong()) {
            throw new JWTVerificationException("Неверный токен.");
        }
        return значение.longValue();
    }
}
