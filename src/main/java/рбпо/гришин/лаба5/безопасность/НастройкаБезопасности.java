package рбпо.гришин.лаба5.безопасность;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import рбпо.гришин.лаба5.модель.Пользователь;
import рбпо.гришин.лаба5.репозиторий.РепозиторийТеатра;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class НастройкаБезопасности {
    @Bean
    public static PasswordEncoder кодировщикПаролей() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService данныеДляВхода(РепозиторийТеатра репозиторий) {
        return логин -> {
            Пользователь пользователь = репозиторий.найтиПользователяПоЛогину(логин.toLowerCase(Locale.ROOT));
            if (пользователь == null) {
                throw new UsernameNotFoundException("Неверные учётные данные.");
            }
            return User.withUsername(пользователь.логин)
                    .password(пользователь.хешПароля)
                    .roles(пользователь.роль)
                    .disabled(!Boolean.TRUE.equals(пользователь.активен))
                    .build();
        };
    }

    @Bean
    public DaoAuthenticationProvider проверкаПароля(UserDetailsService данныеДляВхода, PasswordEncoder кодировщик) {
        DaoAuthenticationProvider проверка = new DaoAuthenticationProvider(данныеДляВхода);
        проверка.setPasswordEncoder(кодировщик);
        return проверка;
    }

    @Bean
    public SecurityFilterChain правилаДоступа(HttpSecurity http, DaoAuthenticationProvider проверка,
                                               СервисJwt jwt, РепозиторийТеатра репозиторий,
                                               ObjectMapper json) throws Exception {
        AuthenticationEntryPoint ошибкаВхода = (запрос, ответ, ошибка) ->
                ответJson(ответ, json, 401, "Требуется вход или неверный токен.");
        AccessDeniedHandler отказВДоступе = (запрос, ответ, ошибка) ->
                ответJson(ответ, json, 403, "Недостаточно прав.");

        http.authenticationProvider(проверка)
                // Использую Bearer без HTTP-сессий и входа через cookie.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(сессии -> сессии.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(контекст -> контекст.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(кэш -> кэш.requestCache(new NullRequestCache()))
                .httpBasic(basic -> basic.disable())
                .formLogin(форма -> форма.disable())
                .logout(выход -> выход.disable())
                .addFilterBefore(new ФильтрJwt(jwt, репозиторий, json), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ошибки -> ошибки
                        .authenticationEntryPoint(ошибкаВхода)
                        .accessDeniedHandler(отказВДоступе))
                .authorizeHttpRequests(доступ -> доступ
                        .requestMatchers(HttpMethod.POST, "/auth/login", "/auth/register", "/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users", "/api/users/").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/users", "/api/users/**",
                                "/api/plays", "/api/theatre-halls", "/api/performances",
                                "/api/performances/*/cancel", "/api/performances/*/complete").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/users/**", "/api/plays/**",
                                "/api/theatre-halls/**", "/api/performances/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/users/**", "/api/plays/**",
                                "/api/theatre-halls/**", "/api/performances/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    static void ответJson(HttpServletResponse ответ, ObjectMapper json, int статус, String сообщение) throws IOException {
        ответ.setStatus(статус);
        if (статус == 401) {
            ответ.setHeader("WWW-Authenticate", "Bearer");
        }
        ответ.setCharacterEncoding("UTF-8");
        ответ.setContentType("application/json");
        json.writeValue(ответ.getWriter(), Map.of("ошибка", сообщение));
    }
}
