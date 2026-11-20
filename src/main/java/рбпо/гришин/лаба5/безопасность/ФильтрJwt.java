package рбпо.гришин.лаба5.безопасность;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import рбпо.гришин.лаба5.модель.Пользователь;
import рбпо.гришин.лаба5.репозиторий.РепозиторийТеатра;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class ФильтрJwt extends OncePerRequestFilter {
    private final СервисJwt jwt;
    private final РепозиторийТеатра репозиторий;
    private final ObjectMapper json;

    public ФильтрJwt(СервисJwt jwt, РепозиторийТеатра репозиторий, ObjectMapper json) {
        this.jwt = jwt;
        this.репозиторий = репозиторий;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest запрос) {
        if (!"POST".equals(запрос.getMethod())) {
            return false;
        }
        String путь = запрос.getRequestURI().substring(запрос.getContextPath().length());
        return путь.equals("/auth/login") || путь.equals("/auth/register") || путь.equals("/auth/refresh");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest запрос, HttpServletResponse ответ, FilterChain цепочка)
            throws ServletException, IOException {
        List<String> заголовки = Collections.list(запрос.getHeaders("Authorization"));
        if (заголовки.isEmpty()) {
            цепочка.doFilter(запрос, ответ);
            return;
        }
        try {
            String заголовок = заголовки.get(0);
            if (заголовки.size() != 1 || !заголовок.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new JWTVerificationException("Неверный токен.");
            }
            String токен = заголовок.substring(7).trim();
            DecodedJWT данные = jwt.проверитьAccess(токен);
            Пользователь пользователь = репозиторий.найтиПользователяПоЛогину(данные.getSubject());
            if (пользователь == null || !Boolean.TRUE.equals(пользователь.активен)
                    || !("USER".equals(пользователь.роль) || "ADMIN".equals(пользователь.роль))) {
                throw new JWTVerificationException("Неверный токен.");
            }
            // Текущую роль беру из базы.
            UsernamePasswordAuthenticationToken вход = UsernamePasswordAuthenticationToken.authenticated(
                    пользователь.логин, null, List.of(new SimpleGrantedAuthority("ROLE_" + пользователь.роль)));
            SecurityContext контекст = SecurityContextHolder.createEmptyContext();
            контекст.setAuthentication(вход);
            SecurityContextHolder.setContext(контекст);
        } catch (JWTVerificationException ошибка) {
            SecurityContextHolder.clearContext();
            НастройкаБезопасности.ответJson(ответ, json, 401, "Требуется вход или неверный токен.");
            return;
        }
        цепочка.doFilter(запрос, ответ);
    }
}
