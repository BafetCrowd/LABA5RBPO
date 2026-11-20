package рбпо.гришин.лаба5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import рбпо.гришин.лаба5.модель.UserSession;
import рбпо.гришин.лаба5.репозиторий.РепозиторийСессий;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ПроверкаApiTest {
    private static final byte[] КЛЮЧ = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private String токенАдминистратора;
    private String токенПокупателя;

    private static final String СПЕКТАКЛЬ =
            "{\"название\":\"Ревизор\",\"длительностьМинут\":120,\"возрастноеОграничение\":12}";
    private static final String ЗАЛ = "{\"название\":\"Большой\",\"числоМест\":10}";
    private static final String ПАРОЛЬ_АДМИНА = "admin";
    private static final String ПАРОЛЬ_ПОКУПАТЕЛЯ = "UserTest123!";
    private static final String ПОЛЬЗОВАТЕЛЬ =
            "{\"имя\":\"Никита\",\"логин\":\"buyer\",\"пароль\":\"UserTest123!\"}";
    private static final String[] ПУТИ = {
            "/api/plays", "/api/theatre-halls", "/api/performances", "/api/tickets", "/api/users"
    };

    // Для тестов создаётся отдельная временная БД. Рабочая база не меняется.
    @DynamicPropertySource
    static void отдельнаяБаза(DynamicPropertyRegistry настройки) throws IOException {
        String путь = Files.createTempDirectory("рбпо-лаба5-тест-").resolve("театр").toString();
        String пароль = UUID.randomUUID().toString();
        String парольАдминистратора = UUID.randomUUID().toString();
        настройки.add("DB_URL", () -> "jdbc:h2:file:" + путь);
        настройки.add("DB_USER", () -> "TEATR_TEST");
        настройки.add("DB_PASSWORD", () -> пароль);
        настройки.add("DB_ADMIN_PASSWORD", () -> парольАдминистратора);
        настройки.add("APP_ADMIN_PASSWORD", () -> ПАРОЛЬ_АДМИНА);
        настройки.add("APP_USER_PASSWORD", () -> ПАРОЛЬ_ПОКУПАТЕЛЯ);
        настройки.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString(КЛЮЧ));
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate база;
    @Autowired
    private PasswordEncoder пароли;
    @MockitoSpyBean
    private РепозиторийСессий сессии;

    @BeforeEach
    void очиститьТестовыеТаблицы() throws Exception {
        for (String таблица : List.of("билеты", "показы", "спектакли", "залы", "пользователи")) {
            база.update("DELETE FROM " + таблица);
        }
        // Сессии удаляются внешним ключом вместе с тестовыми пользователями.
        база.update("INSERT INTO пользователи (имя, логин, хеш_пароля, роль, активен) VALUES (?, ?, ?, 'ADMIN', TRUE)",
                "Администратор", "admin", пароли.encode(ПАРОЛЬ_АДМИНА));
        токенАдминистратора = войти("admin", ПАРОЛЬ_АДМИНА, null).get("accessToken").asText();
        токенПокупателя = null;
    }

    @Test
    void всеПятьОперацийРаботаютДляКаждогоРесурса() throws Exception {
        long[] данные = исходныеДанные();
        long спектакль = данные[0], зал = данные[1], показ = данные[2], пользователь = данные[3];
        long билет = создать("/api/tickets", билет(показ, 3, пользователь));
        long[] номера = {спектакль, зал, показ, билет, пользователь};

        for (int i = 0; i < ПУТИ.length; i++) {
            администратор(get(ПУТИ[i])).andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(i == 4 ? 2 : 1)))
                    .andExpect(jsonPath("$[*].id", hasItem((int) номера[i])));
            администратор(get(ПУТИ[i] + "/" + номера[i])).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(номера[i]));
        }
        отправить(patch("/api/plays/" + спектакль), "{\"название\":\"Гамлет\",\"длительностьМинут\":null}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.название").value("Гамлет"))
                .andExpect(jsonPath("$.длительностьМинут").value(120));
        отправить(patch("/api/theatre-halls/" + зал), "{\"название\":\"Основной\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.числоМест").value(10));
        отправить(patch("/api/performances/" + показ), "{\"базоваяЦена\":700}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.базоваяЦена").value(700));
        отправить(patch("/api/tickets/" + билет), "{\"номерМеста\":4,\"idПользователя\":null}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.номерМеста").value(4))
                .andExpect(jsonPath("$.idПользователя").value(пользователь));
        отправить(patch("/api/users/" + пользователь), "{\"имя\":\"Анна\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.имя").value("Анна"));
        assertEquals("Гамлет", база.queryForObject("SELECT название FROM спектакли WHERE id = ?", String.class, спектакль));
        assertEquals(4, база.queryForObject("SELECT номер_места FROM билеты WHERE id = ?", Integer.class, билет));

        // Сначала удаляем зависимые записи, затем их родителей.
        for (int i : new int[]{3, 2, 0, 1, 4}) {
            String адрес = ПУТИ[i] + "/" + номера[i];
            администратор(delete(адрес)).andExpect(status().isNoContent()).andExpect(content().string(""));
            администратор(get(адрес)).andExpect(status().isNotFound());
            администратор(delete(адрес)).andExpect(status().isNotFound());
            администратор(get(ПУТИ[i])).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(i == 4 ? 1 : 0)));
        }
    }

    @Test
    void неверныеДанныеИДанныеСервераНельзяПередатьВЗапросе() throws Exception {
        long[] данные = исходныеДанные();
        long билет = создать("/api/tickets", билет(данные[2], 1, данные[3]));
        long[] номера = {данные[0], данные[1], данные[2], билет, данные[3]};
        String[] тела = {СПЕКТАКЛЬ, ЗАЛ, показ(данные[0], данные[1]), билет(данные[2], 2, данные[3]), ПОЛЬЗОВАТЕЛЬ};
        for (int i = 0; i < ПУТИ.length; i++) {
            отправить(post(ПУТИ[i]), "{}").andExpect(status().isBadRequest());
            ObjectNode тело = (ObjectNode) json.readTree(тела[i]);
            тело.put("id", 123);
            отправить(post(ПУТИ[i]), тело.toString()).andExpect(status().isBadRequest());
            отправить(patch(ПУТИ[i] + "/" + номера[i]), тело.toString()).andExpect(status().isBadRequest());
        }
        отправить(patch("/api/plays/" + данные[0]), "{\"длительностьМинут\":0}").andExpect(status().isBadRequest());
        отправить(patch("/api/theatre-halls/" + данные[1]), "{\"числоМест\":0}").andExpect(status().isBadRequest());
        отправить(patch("/api/performances/" + данные[2]), "{\"базоваяЦена\":-1}").andExpect(status().isBadRequest());
        отправить(patch("/api/users/" + данные[3]), "{\"имя\":\" \"}").andExpect(status().isBadRequest());
        отправить(post("/api/users"), "{").andExpect(status().isBadRequest());
        for (String поле : List.of("цена", "суммаВозврата", "текущееВремя")) {
            ObjectNode тело = (ObjectNode) json.readTree(билет(данные[2], 2, данные[3]));
            тело.put(поле, 1);
            отправить(post("/api/tickets"), тело.toString()).andExpect(status().isBadRequest());
            отправить(patch("/api/tickets/" + билет), тело.toString()).andExpect(status().isBadRequest());
        }
        отправить(post("/api/performances/" + данные[2] + "/group-booking"),
                "{\"idПользователя\":" + данные[3] + ",\"первоеМесто\":2,\"количество\":1,\"текущееВремя\":1}")
                .andExpect(status().isBadRequest());
        администратор(post("/api/tickets/999999/purchase")).andExpect(status().isNotFound());
        администратор(post("/api/performances/999999/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void связиИЗанятыеМестаЗащищены() throws Exception {
        long[] данные = исходныеДанные();
        long показ = данные[2], пользователь = данные[3];
        long первый = создать("/api/tickets", билет(показ, 3, пользователь));
        long второй = создать("/api/tickets", билет(показ, 4, пользователь));
        отправить(post("/api/tickets"), билет(999999, 1, пользователь)).andExpect(status().isNotFound());
        отправить(post("/api/tickets"), билет(показ, 1, 999999)).andExpect(status().isNotFound());
        отправить(post("/api/tickets"), билет(показ, 11, пользователь)).andExpect(status().isBadRequest());
        отправить(post("/api/tickets"), билет(показ, 3, пользователь)).andExpect(status().isConflict());
        отправить(patch("/api/tickets/" + второй), "{\"номерМеста\":3}").andExpect(status().isConflict());
        отправить(patch("/api/tickets/" + второй), "{\"номерМеста\":4}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.номерМеста").value(4));
        отправить(patch("/api/theatre-halls/" + данные[1]), "{\"числоМест\":2}").andExpect(status().isConflict());
        long другойЗал = создать("/api/theatre-halls", "{\"название\":\"Другой\",\"числоМест\":20}");
        отправить(patch("/api/performances/" + показ), "{\"idЗала\":" + другойЗал + "}")
                .andExpect(status().isConflict());
        отправить(patch("/api/performances/" + показ), "{\"начало\":\"2099-12-01T19:00:00\"}")
                .andExpect(status().isConflict());
        отправить(patch("/api/performances/" + показ), "{\"статус\":\"ОТМЕНЁН\"}").andExpect(status().isConflict());
        отправить(patch("/api/tickets/" + первый), "{\"статус\":\"КУПЛЕН\"}").andExpect(status().isConflict());
        ObjectNode купленный = (ObjectNode) json.readTree(билет(показ, 5, пользователь));
        купленный.put("статус", "КУПЛЕН");
        отправить(post("/api/tickets"), купленный.toString()).andExpect(status().isConflict());
        for (String адрес : List.of("/api/plays/" + данные[0], "/api/theatre-halls/" + данные[1],
                "/api/performances/" + показ, "/api/users/" + пользователь)) {
            администратор(delete(адрес)).andExpect(status().isConflict());
        }
        администратор(get("/api/tickets/" + второй)).andExpect(jsonPath("$.номерМеста").value(4));
        администратор(get("/api/theatre-halls/" + данные[1])).andExpect(jsonPath("$.числоМест").value(10));
        администратор(get("/api/performances/" + показ)).andExpect(jsonPath("$.idЗала").value(данные[1]));
        отправить(patch("/api/theatre-halls/" + данные[1]), "{\"числоМест\":4}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.числоМест").value(4));
    }

    @Test
    void групповаяБроньПокупкаИВозвратСохраняютЦенуИОсвобождаютМесто() throws Exception {
        long[] данные = исходныеДанные();
        long показ = данные[2];
        JsonNode билеты = группа(показ, данные[3], 1, 3);
        long первый = билеты.get(0).get("id").asLong();
        long второй = билеты.get(1).get("id").asLong();
        assertEquals(3, билеты.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1, билеты.get(i).get("номерМеста").asInt());
            assertEquals("БРОНЬ", билеты.get(i).get("статус").asText());
        }
        отправить(patch("/api/performances/" + показ), "{\"базоваяЦена\":650}").andExpect(status().isOk());
        администратор(post("/api/tickets/" + первый + "/purchase")).andExpect(status().isOk())
                .andExpect(jsonPath("$.статус").value("КУПЛЕН"))
                .andExpect(jsonPath("$.цена").value(650));
        администратор(post("/api/tickets/" + первый + "/purchase")).andExpect(status().isConflict());
        администратор(delete("/api/tickets/" + первый)).andExpect(status().isConflict());
        отправить(patch("/api/tickets/" + первый), "{\"номерМеста\":8}").andExpect(status().isConflict());
        отправить(patch("/api/tickets/" + второй), "{\"номерМеста\":1}").andExpect(status().isConflict());
        отправить(patch("/api/performances/" + показ), "{\"базоваяЦена\":800}").andExpect(status().isOk());
        администратор(post("/api/tickets/" + второй + "/return")).andExpect(status().isConflict());
        администратор(post("/api/tickets/" + первый + "/return")).andExpect(status().isOk())
                .andExpect(jsonPath("$.статус").value("ВОЗВРАЩЁН"))
                .andExpect(jsonPath("$.цена").value(650))
                .andExpect(jsonPath("$.суммаВозврата").value(650));
        администратор(post("/api/tickets/" + первый + "/return")).andExpect(status().isConflict());
        отправить(patch("/api/tickets/" + второй), "{\"номерМеста\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.номерМеста").value(1));
        создать("/api/tickets", билет(показ, 2, данные[3]));
        assertEquals(4, база.queryForObject("SELECT COUNT(*) FROM билеты", Integer.class));
    }

    @Test
    void неудачнаяГрупповаяБроньНеЗанимаетЧастьМест() throws Exception {
        long[] данные = исходныеДанные();
        long показ = данные[2], пользователь = данные[3];
        создать("/api/tickets", билет(показ, 3, пользователь));
        отправить(post("/api/performances/" + показ + "/group-booking"), группа(пользователь, 1, 3))
                .andExpect(status().isConflict());
        assertEquals(1, база.queryForObject("SELECT COUNT(*) FROM билеты", Integer.class));
        отправить(post("/api/performances/" + показ + "/group-booking"), группа(пользователь, 9, 3))
                .andExpect(status().isBadRequest());
        assertEquals(1, база.queryForObject("SELECT COUNT(*) FROM билеты", Integer.class));
        // После неудачной операции первые два места всё ещё доступны.
        assertEquals(2, группа(показ, пользователь, 1, 2).size());
        отправить(post("/api/performances/" + показ + "/group-booking"), группа(пользователь, 4, 0))
                .andExpect(status().isBadRequest());
    }

    @Test
    void отменаПоказаОтменяетБрониИВозвращаетОплату() throws Exception {
        long[] данные = исходныеДанные();
        long показ = данные[2];
        JsonNode билеты = группа(показ, данные[3], 1, 3);
        long купленный = билеты.get(0).get("id").asLong();
        long бронь = билеты.get(1).get("id").asLong();
        long возвращённый = билеты.get(2).get("id").asLong();
        администратор(post("/api/tickets/" + купленный + "/purchase")).andExpect(status().isOk());
        администратор(post("/api/tickets/" + возвращённый + "/purchase")).andExpect(status().isOk());
        администратор(post("/api/tickets/" + возвращённый + "/return")).andExpect(status().isOk());
        администратор(post("/api/performances/" + показ + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.статус").value("ОТМЕНЁН"));
        администратор(get("/api/tickets/" + купленный)).andExpect(jsonPath("$.статус").value("ОТМЕНЁН"))
                .andExpect(jsonPath("$.суммаВозврата").value(500));
        администратор(get("/api/tickets/" + бронь)).andExpect(jsonPath("$.статус").value("ОТМЕНЁН"))
                .andExpect(jsonPath("$.суммаВозврата").value(0));
        администратор(get("/api/tickets/" + возвращённый)).andExpect(jsonPath("$.статус").value("ВОЗВРАЩЁН"))
                .andExpect(jsonPath("$.суммаВозврата").value(500));
        администратор(post("/api/performances/" + показ + "/cancel")).andExpect(status().isConflict());
        администратор(post("/api/tickets/" + бронь + "/purchase")).andExpect(status().isConflict());
        отправить(post("/api/performances/" + показ + "/group-booking"), группа(данные[3], 4, 2))
                .andExpect(status().isConflict());
        отправить(patch("/api/performances/" + показ), "{\"базоваяЦена\":900}").andExpect(status().isConflict());
        assertEquals(0, база.queryForObject("SELECT COUNT(*) FROM билеты WHERE активное_место IS NOT NULL", Integer.class));
        // Отмена освобождает места, но история билетов должна помещаться в существующий зал.
        отправить(patch("/api/theatre-halls/" + данные[1]), "{\"числоМест\":2}").andExpect(status().isConflict());
    }

    @Test
    void завершениеУчитываетВремяСервераИДлительностьСпектакля() throws Exception {
        long[] данные = исходныеДанные();
        long показ = данные[2];
        JsonNode билеты = группа(показ, данные[3], 1, 2);
        long купленный = билеты.get(0).get("id").asLong();
        long бронь = билеты.get(1).get("id").asLong();
        администратор(post("/api/tickets/" + купленный + "/purchase")).andExpect(status().isOk());
        администратор(post("/api/performances/" + показ + "/complete")).andExpect(status().isConflict());
        // В тестовой БД переносим начало назад: ждать два часа во время теста не нужно.
        база.update("UPDATE показы SET начало = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now().minusMinutes(30)), показ);
        администратор(post("/api/performances/" + показ + "/complete")).andExpect(status().isConflict());
        администратор(post("/api/tickets/" + бронь + "/purchase")).andExpect(status().isConflict());
        администратор(post("/api/tickets/" + купленный + "/return")).andExpect(status().isConflict());
        администратор(post("/api/performances/" + показ + "/cancel")).andExpect(status().isConflict());
        отправить(post("/api/tickets"), билет(показ, 3, данные[3])).andExpect(status().isConflict());
        база.update("UPDATE показы SET начало = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now().minusHours(3)), показ);
        администратор(post("/api/performances/" + показ + "/complete")).andExpect(status().isOk())
                .andExpect(jsonPath("$.статус").value("ЗАВЕРШЁН"));
        администратор(get("/api/tickets/" + купленный)).andExpect(jsonPath("$.статус").value("ИСПОЛЬЗОВАН"))
                .andExpect(jsonPath("$.суммаВозврата").value(0));
        администратор(get("/api/tickets/" + бронь)).andExpect(jsonPath("$.статус").value("ОТМЕНЁН"));
        администратор(post("/api/performances/" + показ + "/complete")).andExpect(status().isConflict());
    }

    @Test
    void одновременныеБрониНеПродаютОдниМестаДважды() throws Exception {
        long[] данные = исходныеДанные();
        var потоки = Executors.newFixedThreadPool(2);
        var старт = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> запрос = () -> {
                старт.await();
                return отправить(post("/api/performances/" + данные[2] + "/group-booking"), группа(данные[3], 1, 2))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> первый = потоки.submit(запрос);
            Future<Integer> второй = потоки.submit(запрос);
            старт.countDown();
            List<Integer> ответы = java.util.stream.Stream.of(первый.get(15, TimeUnit.SECONDS), второй.get(15, TimeUnit.SECONDS))
                    .sorted().toList();
            assertEquals(List.of(201, 409), ответы);
            assertEquals(2, база.queryForObject("SELECT COUNT(*) FROM билеты", Integer.class));
        } finally {
            потоки.shutdownNow();
        }
    }

    @Test
    void базаПроверяетСвязиОграниченияИПраваПользователя() throws Exception {
        long[] данные = исходныеДанные();
        long билет = создать("/api/tickets", билет(данные[2], 1, данные[3]));
        for (String sql : List.of(
                "UPDATE залы SET число_мест = 0",
                "UPDATE пользователи SET имя = NULL",
                "UPDATE показы SET базовая_цена = -1",
                "UPDATE билеты SET пользователь_id = 999999",
                "UPDATE билеты SET сумма_возврата = цена + 1",
                "UPDATE user_sessions SET status = 'UNKNOWN'",
                "UPDATE user_sessions SET username = 'несуществующий'",
                "UPDATE user_sessions SET access_token = NULL",
                "DELETE FROM спектакли",
                "INSERT INTO билеты (показ_id, номер_места, пользователь_id, статус, цена) SELECT показ_id, номер_места, пользователь_id, статус, цена FROM билеты WHERE id = " + билет)) {
            assertThrows(DataAccessException.class, () -> база.update(sql), sql);
        }
        for (String sql : List.of("CREATE TABLE лишняя_таблица (id INT)", "DROP TABLE билеты",
                "CREATE USER посторонний PASSWORD 'только-для-теста'")) {
            assertThrows(DataAccessException.class, () -> база.execute(sql), sql);
        }
        assertEquals(1, база.queryForObject("SELECT COUNT(*) FROM билеты", Integer.class));
        assertEquals("TEATR_TEST", база.queryForObject("SELECT CURRENT_USER", String.class));
    }

    @Test
    void покупательВидитТолькоСвоиБилетыИНельзяПодменитьВладельца() throws Exception {
        long[] данные = исходныеДанные();
        long другой = создать("/api/users", пользователь("other", ПАРОЛЬ_ПОКУПАТЕЛЯ));
        long свойБилет = создать("/api/tickets", билет(данные[2], 1, данные[3]));
        long чужойБилет = создать("/api/tickets", билет(данные[2], 2, другой));
        покупатель(get("/api/tickets")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(свойБилет));
        покупатель(get("/api/tickets/" + чужойБилет)).andExpect(status().isForbidden());
        покупатель(delete("/api/tickets/" + чужойБилет)).andExpect(status().isForbidden());
        покупатель(patch("/api/tickets/" + чужойБилет).contentType(APPLICATION_JSON).content("{\"номерМеста\":8}"))
                .andExpect(status().isForbidden());
        for (String операция : List.of("purchase", "return")) {
            покупатель(post("/api/tickets/" + чужойБилет + "/" + операция)).andExpect(status().isForbidden());
        }
        покупатель(post("/api/tickets").contentType(APPLICATION_JSON).content(билет(данные[2], 3, другой)))
                .andExpect(status().isForbidden());
        покупатель(post("/api/performances/" + данные[2] + "/group-booking")
                        .contentType(APPLICATION_JSON).content(группа(другой, 3, 2)))
                .andExpect(status().isForbidden());
        покупатель(patch("/api/tickets/" + свойБилет).contentType(APPLICATION_JSON)
                        .content("{\"idПользователя\":" + другой + "}"))
                .andExpect(status().isForbidden());
        покупатель(get("/api/tickets/" + свойБилет)).andExpect(status().isOk())
                .andExpect(jsonPath("$.idПользователя").value(данные[3]));
        покупатель(post("/api/tickets/" + свойБилет + "/purchase")).andExpect(status().isOk())
                .andExpect(jsonPath("$.статус").value("КУПЛЕН"));
        покупатель(post("/api/tickets/" + свойБилет + "/return")).andExpect(status().isOk())
                .andExpect(jsonPath("$.статус").value("ВОЗВРАЩЁН"));
        покупатель(post("/api/performances/" + данные[2] + "/group-booking")
                        .contentType(APPLICATION_JSON).content(группа(данные[3], 3, 2)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$", hasSize(2)));
        покупатель(delete("/api/tickets/" + свойБилет)).andExpect(status().isNoContent());
    }

    @Test
    void каталогДоступенПокупателюНоИзмененияТолькоАдминистратору() throws Exception {
        long[] данные = исходныеДанные();
        for (String путь : List.of("/api/plays", "/api/theatre-halls", "/api/performances")) {
            покупатель(get(путь)).andExpect(status().isOk());
            покупатель(post(путь).contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        покупатель(patch("/api/plays/" + данные[0]).contentType(APPLICATION_JSON).content("{\"название\":\"Новое\"}"))
                .andExpect(status().isForbidden());
        покупатель(delete("/api/theatre-halls/" + данные[1])).andExpect(status().isForbidden());
        покупатель(get("/api/users")).andExpect(status().isForbidden());
        покупатель(post("/api/users").contentType(APPLICATION_JSON).content(пользователь("other", ПАРОЛЬ_ПОКУПАТЕЛЯ)))
                .andExpect(status().isForbidden());
        покупатель(patch("/api/users/" + данные[3]).contentType(APPLICATION_JSON).content("{\"роль\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        покупатель(delete("/api/users/" + данные[3])).andExpect(status().isForbidden());
        покупатель(get("/api/users/" + данные[3])).andExpect(status().isOk())
                .andExpect(jsonPath("$.хешПароля").doesNotExist());
        long админ = база.queryForObject("SELECT id FROM пользователи WHERE логин = 'admin'", Long.class);
        покупатель(get("/api/users/" + админ)).andExpect(status().isForbidden());
        for (String операция : List.of("cancel", "complete")) {
            покупатель(post("/api/performances/" + данные[2] + "/" + операция)).andExpect(status().isForbidden());
        }
    }

    @Test
    void нельзяУдалитьЗаблокироватьИлиПонизитьПоследнегоАдминистратора() throws Exception {
        long админ = база.queryForObject("SELECT id FROM пользователи WHERE логин = 'admin'", Long.class);
        администратор(delete("/api/users/" + админ)).andExpect(status().isConflict());
        отправить(patch("/api/users/" + админ), "{\"активен\":false}").andExpect(status().isConflict());
        отправить(patch("/api/users/" + админ), "{\"роль\":\"USER\"}").andExpect(status().isConflict());
        ObjectNode второйАдмин = (ObjectNode) json.readTree(пользователь("second_admin", ПАРОЛЬ_ПОКУПАТЕЛЯ));
        второйАдмин.put("роль", "ADMIN");
        long второй = создать("/api/users", второйАдмин.toString());
        отправить(patch("/api/users/" + админ), "{\"роль\":\"USER\"}").andExpect(status().isOk());
        администратор(get("/api/users")).andExpect(status().isForbidden());
        String второйТокен = войти("second_admin", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("accessToken").asText();
        mvc.perform(patch("/api/users/" + админ).header("Authorization", "Bearer " + второйТокен)
                        .contentType(APPLICATION_JSON).content("{\"роль\":\"ADMIN\"}"))
                .andExpect(status().isOk());
        отправить(patch("/api/users/" + второй), "{\"активен\":false}").andExpect(status().isOk());
        администратор(delete("/api/users/" + второй)).andExpect(status().isNoContent());
    }

    @Test
    void входИРегистрацияВыдаютТолькоТокеныИПроверяютДанные() throws Exception {
        for (String путь : List.of("/auth/login", "/auth/register", "/auth/refresh")) {
            публичныйЗапрос(путь, "{}").andExpect(status().isBadRequest());
        }
        публичныйЗапрос("/auth/login", "{\"username\":\"admin\",\"password\":\"wrong\"}")
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        публичныйЗапрос("/auth/login", "{\"username\":\"absent\",\"password\":\"wrong\"}")
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/plays")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/plays").with(httpBasic("admin", ПАРОЛЬ_АДМИНА))).andExpect(status().isUnauthorized());
        for (String пароль : List.of("admin", "Ab1", "lowercase1", "UPPERCASE1", "NoDigitsHere", "Ab1" + "я".repeat(35))) {
            публичныйЗапрос("/auth/register", пользователь("buyer", пароль)).andExpect(status().isBadRequest());
        }
        for (String логин : List.of("ab", "покупатель", "has space", "a".repeat(41))) {
            публичныйЗапрос("/auth/register", пользователь(логин, ПАРОЛЬ_ПОКУПАТЕЛЯ)).andExpect(status().isBadRequest());
        }
        ObjectNode повышение = (ObjectNode) json.readTree(ПОЛЬЗОВАТЕЛЬ);
        повышение.put("роль", "ADMIN");
        публичныйЗапрос("/auth/register", повышение.toString()).andExpect(status().isBadRequest());
        JsonNode пара = ответ(публичныйЗапрос("/auth/register", пользователь("BuYeR", ПАРОЛЬ_ПОКУПАТЕЛЯ))
                .andExpect(status().isCreated()));
        assertEquals(2, пара.size());
        String access = пара.get("accessToken").asText();
        assertTrue(!пара.get("refreshToken").asText().isBlank());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.логин").value("buyer"))
                .andExpect(jsonPath("$.роль").value("USER")).andExpect(jsonPath("$.хешПароля").doesNotExist());
        String хеш = база.queryForObject("SELECT хеш_пароля FROM пользователи WHERE логин = 'buyer'", String.class);
        assertNotEquals(ПАРОЛЬ_ПОКУПАТЕЛЯ, хеш);
        assertTrue(хеш.startsWith("$2") && пароли.matches(ПАРОЛЬ_ПОКУПАТЕЛЯ, хеш));
        публичныйЗапрос("/auth/register", пользователь("BUYER", ПАРОЛЬ_ПОКУПАТЕЛЯ)).andExpect(status().isConflict());
        JsonNode вход = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null);
        assertEquals(2, вход.size());
        администратор(get("/api/users")).andExpect(jsonPath("$[*].хешПароля").isEmpty());
        // Английские имена полей регистрации тоже поддерживаются.
        публичныйЗапрос("/auth/register", "{\"name\":\"Анна\",\"username\":\"anna_new\",\"password\":\"UserTest123!\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void подписьТипИСрокJwtПроверяютсяДляКаждогоЗапроса() throws Exception {
        создать("/api/users", ПОЛЬЗОВАТЕЛЬ);
        JsonNode пара = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null);
        String refresh = пара.get("refreshToken").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + refresh)).andExpect(status().isUnauthorized());
        обновить(пара.get("accessToken").asText()).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer это-не-jwt")).andExpect(status().isUnauthorized());
        for (String поле : List.of("sub", "jti", "iat", "exp", "type")) {
            Map<String, Object> данные = поляJwt("buyer", "access");
            данные.remove(поле);
            проверитьОтказJwt(подписать(данные));
        }
        for (Map<String, Object> замена : List.of(
                Map.<String, Object>of("sub", 123), Map.<String, Object>of("sub", ""),
                Map.<String, Object>of("jti", 123), Map.<String, Object>of("jti", ""),
                Map.<String, Object>of("iat", Instant.now().getEpochSecond() - 10.5),
                Map.<String, Object>of("exp", Instant.now().getEpochSecond() + 100.5),
                Map.<String, Object>of("iat", Instant.now().plusSeconds(60).getEpochSecond()),
                Map.<String, Object>of("exp", Instant.now().minusSeconds(1).getEpochSecond()),
                Map.<String, Object>of("type", "unknown"))) {
            Map<String, Object> данные = поляJwt("buyer", "access");
            данные.putAll(замена);
            проверитьОтказJwt(подписать(данные));
        }
        Map<String, Object> данные = поляJwt("buyer", "access");
        проверитьОтказJwt(JWT.create().withPayload(данные).sign(Algorithm.none()));
        проверитьОтказJwt(JWT.create().withPayload(данные).sign(Algorithm.HMAC256("другой-тестовый-ключ")));
    }

    @Test
    void обновлениеПомечаетСтаруюСессиюИСоздаётНовуюСТемЖеУстройством() throws Exception {
        создать("/api/users", ПОЛЬЗОВАТЕЛЬ);
        JsonNode старая = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, "телефон");
        String старыйRefresh = старая.get("refreshToken").asText();
        JsonNode новая = ответ(обновить(старыйRefresh).andExpect(status().isOk()));
        assertEquals(2, новая.size());
        assertEquals("USED", статусСессии(старыйRefresh));
        String новыйRefresh = новая.get("refreshToken").asText();
        assertEquals("ACTIVE", статусСессии(новыйRefresh));
        assertNotEquals(JWT.decode(старыйRefresh).getId(), JWT.decode(новыйRefresh).getId());
        assertNotEquals(старая.get("accessToken").asText(), новая.get("accessToken").asText());
        assertEquals("телефон", база.queryForObject("SELECT device_id FROM user_sessions WHERE refresh_token = ?", String.class, новыйRefresh));
        assertEquals("buyer", база.queryForObject("SELECT username FROM user_sessions WHERE refresh_token = ?", String.class, новыйRefresh));
        assertEquals(2, числоСессийПокупателя());
        DecodedJWT access = JWT.decode(новая.get("accessToken").asText());
        DecodedJWT refresh = JWT.decode(новыйRefresh);
        assertEquals("access", access.getClaim("type").asString());
        assertEquals("refresh", refresh.getClaim("type").asString());
        assertEquals("buyer", access.getSubject());
        assertEquals(access.getExpiresAtAsInstant(), база.queryForObject(
                "SELECT access_token_expiry FROM user_sessions WHERE refresh_token = ?", java.time.OffsetDateTime.class, новыйRefresh).toInstant());
        assertEquals(refresh.getExpiresAtAsInstant(), база.queryForObject(
                "SELECT refresh_token_expiry FROM user_sessions WHERE refresh_token = ?", java.time.OffsetDateTime.class, новыйRefresh).toInstant());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + новая.get("accessToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void повторноеИспользованиеRefreshСохраняетОтзывДажеПриОтвете401() throws Exception {
        создать("/api/users", ПОЛЬЗОВАТЕЛЬ);
        String старый = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        String новый = ответ(обновить(старый).andExpect(status().isOk())).get("refreshToken").asText();
        обновить(старый).andExpect(status().isUnauthorized());
        assertEquals("REVOKED", статусСессии(старый));
        assertEquals("ACTIVE", статусСессии(новый));
        assertEquals(2, числоСессийПокупателя());
        обновить(старый).andExpect(status().isUnauthorized());
        assertEquals("REVOKED", статусСессии(старый));
        assertEquals(2, числоСессийПокупателя());
    }

    @Test
    void повторИспользованногоRefreshОтзываетсяДажеПослеБлокировкиПользователя() throws Exception {
        long пользователь = создать("/api/users", ПОЛЬЗОВАТЕЛЬ);
        String старый = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        String новый = ответ(обновить(старый).andExpect(status().isOk())).get("refreshToken").asText();
        отправить(patch("/api/users/" + пользователь), "{\"активен\":false}").andExpect(status().isOk());
        обновить(старый).andExpect(status().isUnauthorized());
        assertEquals("REVOKED", статусСессии(старый));
        assertEquals("ACTIVE", статусСессии(новый));
        assertEquals(2, числоСессийПокупателя());
        обновить(новый).andExpect(status().isUnauthorized());
        assertEquals(2, числоСессийПокупателя());
    }

    @Test
    void неподходящаяСессияИЗаблокированныйПользовательНеОбновляютТокен() throws Exception {
        long пользователь = создать("/api/users", ПОЛЬЗОВАТЕЛЬ);
        обновить(подписать(поляJwt("buyer", "refresh"))).andExpect(status().isUnauthorized());
        String отозванный = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        база.update("UPDATE user_sessions SET status = 'REVOKED' WHERE refresh_token = ?", отозванный);
        обновить(отозванный).andExpect(status().isUnauthorized());
        String истёкшийВБазе = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        база.update("UPDATE user_sessions SET refresh_token_expiry = ? WHERE refresh_token = ?",
                java.time.OffsetDateTime.now().minusDays(1), истёкшийВБазе);
        обновить(истёкшийВБазе).andExpect(status().isUnauthorized());
        String несовпадающийСрок = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        база.update("UPDATE user_sessions SET refresh_token_expiry = DATEADD('SECOND', 1, refresh_token_expiry) WHERE refresh_token = ?", несовпадающийСрок);
        обновить(несовпадающийСрок).andExpect(status().isUnauthorized());
        String ещёСессия = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        Map<String, Object> прошедшийСрок = поляJwt("buyer", "refresh");
        прошедшийСрок.put("iat", Instant.now().minusSeconds(120).getEpochSecond());
        прошедшийСрок.put("exp", Instant.now().minusSeconds(60).getEpochSecond());
        String просроченныйJwt = подписать(прошедшийСрок);
        база.update("UPDATE user_sessions SET refresh_token = ?, refresh_token_expiry = ? WHERE refresh_token = ?",
                просроченныйJwt, java.time.OffsetDateTime.ofInstant(JWT.decode(просроченныйJwt).getExpiresAtAsInstant(), java.time.ZoneOffset.UTC), ещёСессия);
        обновить(просроченныйJwt).andExpect(status().isUnauthorized());
        String другаяСессия = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        Map<String, Object> чужойSub = поляJwt("admin", "refresh");
        String изменённый = подписать(чужойSub);
        база.update("UPDATE user_sessions SET refresh_token = ?, refresh_token_expiry = ? WHERE refresh_token = ?",
                изменённый, java.time.OffsetDateTime.ofInstant(JWT.decode(изменённый).getExpiresAtAsInstant(), java.time.ZoneOffset.UTC), другаяСессия);
        обновить(изменённый).andExpect(status().isUnauthorized());
        JsonNode пара = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null);
        int до = числоСессийПокупателя();
        отправить(patch("/api/users/" + пользователь), "{\"активен\":false}").andExpect(status().isOk());
        обновить(пара.get("refreshToken").asText()).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + пара.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        публичныйЗапрос("/auth/login", "{\"username\":\"buyer\",\"password\":\"UserTest123!\"}")
                .andExpect(status().isUnauthorized());
        assertEquals(до, числоСессийПокупателя());
        администратор(delete("/api/users/" + пользователь)).andExpect(status().isNoContent());
        обновить(пара.get("refreshToken").asText()).andExpect(status().isUnauthorized());
        assertEquals(0, числоСессийПокупателя());
    }

    @Test
    void техническийСбойОткатываетИРотациюИРегистрацию() throws Exception {
        создать("/api/users", ПОЛЬЗОВАТЕЛЬ);
        String refresh = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        doThrow(new DataAccessResourceFailureException("Искусственный сбой записи сессии"))
                .when(сессии).сохранить(any(UserSession.class));
        try {
            обновить(refresh).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.ошибка").isString());
            assertEquals("ACTIVE", статусСессии(refresh));
            assertEquals(1, числоСессийПокупателя());
            публичныйЗапрос("/auth/register", пользователь("rollback_user", ПАРОЛЬ_ПОКУПАТЕЛЯ))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.ошибка").isString());
            assertEquals(0, база.queryForObject("SELECT COUNT(*) FROM пользователи WHERE логин = 'rollback_user'", Integer.class));
        } finally {
            reset(сессии);
        }
    }

    @Test
    void дваОдновременныхRefreshНеСоздаютДвеНовыеСессии() throws Exception {
        создать("/api/users", ПОЛЬЗОВАТЕЛЬ);
        String refresh = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("refreshToken").asText();
        var потоки = Executors.newFixedThreadPool(2);
        var старт = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> запрос = () -> {
                старт.await();
                return обновить(refresh).andReturn().getResponse().getStatus();
            };
            Future<Integer> первый = потоки.submit(запрос);
            Future<Integer> второй = потоки.submit(запрос);
            старт.countDown();
            List<Integer> ответы = java.util.stream.Stream.of(первый.get(15, TimeUnit.SECONDS), второй.get(15, TimeUnit.SECONDS))
                    .sorted().toList();
            assertEquals(List.of(200, 401), ответы);
            assertEquals("REVOKED", статусСессии(refresh));
            assertEquals(2, числоСессийПокупателя());
            assertEquals(1, база.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE username = 'buyer' AND status = 'ACTIVE'", Integer.class));
        } finally {
            потоки.shutdownNow();
        }
    }

    private ResultActions публичныйЗапрос(String путь, String тело) throws Exception {
        return mvc.perform(post(путь).contentType(APPLICATION_JSON).content(тело));
    }

    private JsonNode войти(String логин, String пароль, String устройство) throws Exception {
        ObjectNode тело = json.createObjectNode();
        тело.put("username", логин);
        тело.put("password", пароль);
        if (устройство != null) тело.put("deviceId", устройство);
        return ответ(публичныйЗапрос("/auth/login", тело.toString()).andExpect(status().isOk()));
    }

    private ResultActions обновить(String refresh) throws Exception {
        return публичныйЗапрос("/auth/refresh", json.createObjectNode().put("refreshToken", refresh).toString());
    }

    private String статусСессии(String refresh) {
        return база.queryForObject("SELECT status FROM user_sessions WHERE refresh_token = ?", String.class, refresh);
    }

    private int числоСессийПокупателя() {
        return база.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE username = 'buyer'", Integer.class);
    }

    private Map<String, Object> поляJwt(String логин, String тип) {
        Map<String, Object> поля = new LinkedHashMap<>();
        поля.put("sub", логин);
        поля.put("jti", UUID.randomUUID().toString());
        поля.put("type", тип);
        поля.put("iat", Instant.now().minusSeconds(10).getEpochSecond());
        поля.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
        return поля;
    }

    private String подписать(Map<String, Object> поля) {
        return JWT.create().withPayload(поля).sign(Algorithm.HMAC256(КЛЮЧ));
    }

    private void проверитьОтказJwt(String токен) throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + токен)).andExpect(status().isUnauthorized());
    }

    private String пользователь(String логин, String пароль) throws Exception {
        ObjectNode тело = json.createObjectNode();
        тело.put("имя", "Никита");
        тело.put("логин", логин);
        тело.put("пароль", пароль);
        return тело.toString();
    }

    private long[] исходныеДанные() throws Exception {
        long спектакль = создать("/api/plays", СПЕКТАКЛЬ);
        long зал = создать("/api/theatre-halls", ЗАЛ);
        long пользователь = создать("/api/users", ПОЛЬЗОВАТЕЛЬ);
        long показ = создать("/api/performances", показ(спектакль, зал));
        return new long[]{спектакль, зал, показ, пользователь};
    }

    private ResultActions администратор(MockHttpServletRequestBuilder запрос) throws Exception {
        return mvc.perform(запрос.header("Authorization", "Bearer " + токенАдминистратора));
    }

    private ResultActions покупатель(MockHttpServletRequestBuilder запрос) throws Exception {
        if (токенПокупателя == null) {
            токенПокупателя = войти("buyer", ПАРОЛЬ_ПОКУПАТЕЛЯ, null).get("accessToken").asText();
        }
        return mvc.perform(запрос.header("Authorization", "Bearer " + токенПокупателя));
    }

    private ResultActions отправить(MockHttpServletRequestBuilder запрос, String тело) throws Exception {
        return администратор(запрос.contentType(APPLICATION_JSON).content(тело));
    }

    private long создать(String путь, String тело) throws Exception {
        return ответ(отправить(post(путь), тело).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())).get("id").asLong();
    }

    private JsonNode ответ(ResultActions результат) throws Exception {
        return json.readTree(результат.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode группа(long показ, long пользователь, int первоеМесто, int количество) throws Exception {
        return ответ(отправить(post("/api/performances/" + показ + "/group-booking"),
                группа(пользователь, первоеМесто, количество)).andExpect(status().isCreated()));
    }

    private String группа(long пользователь, int первоеМесто, int количество) {
        return "{\"idПользователя\":%d,\"первоеМесто\":%d,\"количество\":%d}"
                .formatted(пользователь, первоеМесто, количество);
    }

    private String показ(long спектакль, long зал) {
        return """
                {"idСпектакля":%d,"idЗала":%d,"начало":"%s","базоваяЦена":500,"статус":"ЗАПЛАНИРОВАН"}
                """.formatted(спектакль, зал, LocalDateTime.now().plusDays(30).withNano(0));
    }

    private String билет(long показ, int место, long пользователь) {
        return """
                {"idПоказа":%d,"номерМеста":%d,"idПользователя":%d,"статус":"БРОНЬ"}
                """.formatted(показ, место, пользователь);
    }
}
