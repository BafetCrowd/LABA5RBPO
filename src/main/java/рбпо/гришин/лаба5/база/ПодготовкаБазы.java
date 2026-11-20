package рбпо.гришин.лаба5.база;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import рбпо.гришин.лаба5.сервис.СервисПользователей;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@Configuration
public class ПодготовкаБазы {
    @Bean
    public DataSource источникДанных(Environment настройки, PasswordEncoder кодировщик) throws Exception {
        String адрес = настройки.getRequiredProperty("DB_URL");
        String пользователь = настройки.getRequiredProperty("DB_USER");
        String пароль = настройки.getRequiredProperty("DB_PASSWORD");
        String парольАдминистратора = настройки.getRequiredProperty("DB_ADMIN_PASSWORD");
        if (пользователь.isBlank() || пользователь.equalsIgnoreCase("SA")
                || пароль.isBlank() || парольАдминистратора.isBlank()) {
            throw new IllegalArgumentException("Укажите отдельного пользователя БД и непустые пароли в .env");
        }

        HikariDataSource источник = new HikariDataSource();
        источник.setJdbcUrl(адрес);
        источник.setUsername(пользователь);
        источник.setPassword(пароль);
        источник.setMaximumPoolSize(5);
        источник.setPoolName("БазаТеатра");

        // Через SA подготавливаю только схему и права.
        try (Connection соединение = DriverManager.getConnection(адрес, "SA", парольАдминистратора);
             Statement команда = соединение.createStatement()) {
            выполнитьСкрипт(соединение, "база/таблицы.sql");

            String имяSql = "\"" + пользователь.replace("\"", "\"\"") + "\"";
            try (PreparedStatement создание = соединение.prepareStatement("CREATE USER IF NOT EXISTS " + имяSql + " PASSWORD ?")) {
                создание.setString(1, пароль);
                создание.execute();
            }
            команда.execute("ALTER USER " + имяSql + " ADMIN FALSE");
            команда.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON спектакли, залы, пользователи, показы, билеты TO " + имяSql);
            // DELETE нужен H2 для каскадного удаления сессий.
            команда.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON user_sessions TO " + имяSql);
            команда.execute("GRANT SELECT, UPDATE ON блокировка TO " + имяSql);

            boolean нужныПримеры;
            try (ResultSet результат = команда.executeQuery("SELECT COUNT(*) FROM подготовка")) {
                результат.next();
                нужныПримеры = результат.getInt(1) == 0;
            }
            if (нужныПримеры) {
                соединение.setAutoCommit(false);
                try {
                    String парольAdmin = настройки.getRequiredProperty("APP_ADMIN_PASSWORD");
                    String парольUser = настройки.getRequiredProperty("APP_USER_PASSWORD");
                    // admin/admin оставляю только для начального аккаунта.
                    if (!"admin".equals(парольAdmin)) {
                        СервисПользователей.проверитьПароль(парольAdmin);
                    }
                    СервисПользователей.проверитьПароль(парольUser);
                    создатьАккаунт(соединение, "Администратор", "admin", "ADMIN", кодировщик.encode(парольAdmin));
                    создатьАккаунт(соединение, "Гришин Никита", "nikita", "USER", кодировщик.encode(парольUser));
                    создатьАккаунт(соединение, "Анна", "anna", "USER", кодировщик.encode(парольUser));
                    выполнитьСкрипт(соединение, "база/примеры.sql");
                    команда.executeUpdate("INSERT INTO подготовка (id) VALUES (1)");
                    соединение.commit();
                } catch (Exception ошибка) {
                    соединение.rollback();
                    throw ошибка;
                }
            }

            try (Connection проверка = источник.getConnection()) {
                проверка.isValid(5);
            }
        } catch (Exception ошибка) {
            источник.close();
            throw ошибка;
        }
        return источник;
    }

    private void создатьАккаунт(Connection соединение, String имя, String логин,
                                String роль, String хеш) throws Exception {
        try (PreparedStatement команда = соединение.prepareStatement(
                "INSERT INTO пользователи (имя, логин, хеш_пароля, роль, активен) VALUES (?, ?, ?, ?, TRUE)")) {
            команда.setString(1, имя);
            команда.setString(2, логин);
            команда.setString(3, хеш);
            команда.setString(4, роль);
            команда.executeUpdate();
        }
    }

    private void выполнитьСкрипт(Connection соединение, String путь) throws Exception {
        ClassPathResource файл = new ClassPathResource(путь);
        ScriptUtils.executeSqlScript(соединение, new EncodedResource(файл, StandardCharsets.UTF_8));
    }
}
