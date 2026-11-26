package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.repository.DocumentFolderRepository;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.UserRepository;
import ru.afina.security.AuthUser;
import ru.afina.security.PiiEncryptor;
import ru.afina.service.DocumentService;
import ru.afina.service.FolderService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * База интеграционных тестов REST API.
 *
 * <p>Поднимает полный контекст на H2 (профиль {@code test}), создаёт
 * пользователей, разделы и документы через реальные сервисы — так тесты
 * проверяют всю цепочку: контроллер → сервис → репозиторий → БД.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestBase {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected DocumentRepository documentRepository;
    @Autowired
    protected DocumentFolderRepository folderRepository;
    @Autowired
    protected PiiEncryptor pii;
    @Autowired
    protected FolderService folderService;
    @Autowired
    protected DocumentService documentService;
    @Autowired
    protected org.springframework.context.ApplicationContext applicationContext;

    // ──────────────────────────── аутентификация ────────────────────────────

    /** Принципал для MockMvc-запроса. */
    protected static AuthUser principal(User u) {
        return new AuthUser(u.getId(), u.getEmail(), u.getFullName(), u.getRole().name());
    }

    /** Запрос от имени пользователя (с CSRF-токеном). */
    protected MockHttpServletRequestBuilder as(User u, MockHttpServletRequestBuilder builder) {
        return builder.with(user(principal(u))).with(csrf());
    }

    /** Запрос от имени пользователя без CSRF (для GET). */
    protected MockHttpServletRequestBuilder asGet(User u, MockHttpServletRequestBuilder builder) {
        return builder.with(user(principal(u)));
    }

    protected static SimpleGrantedAuthority authority(Role role) {
        return new SimpleGrantedAuthority(role.name());
    }

    // ──────────────────────────── тестовые данные ────────────────────────────

    protected User createUser(String email, String fullName, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setEmailHash(pii.hash(email));
        u.setFullName(fullName);
        u.setRole(role);
        u.setEnabled(true);
        u.setConsentSignedAt(Instant.now());
        u.setFullNameConfirmedAt(Instant.now());
        u.setCreatedAt(Instant.now());
        return userRepository.save(u);
    }

    protected User createClient() {
        return createUser("client-" + UUID.randomUUID() + "@test.local",
                "Тестовый Клиент", Role.ROLE_USER);
    }

    protected User createAdmin() {
        return createUser("admin-" + UUID.randomUUID() + "@test.local",
                "Тестовый Администратор", Role.ROLE_ADMIN);
    }

    protected DocumentFolder createFolder(User owner, String name) {
        return folderService.create(owner, name);
    }

    protected Document createDocument(User owner, DocumentFolder folder, String name) {
        try {
            byte[] content = ("Содержимое документа " + name).getBytes(StandardCharsets.UTF_8);
            return documentService.createDocument(content, name, "text/plain", owner, folder);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать тестовый документ", e);
        }
    }

    /** Создаёт документ в автоматическом разделе по умолчанию. */
    protected Document createDocument(User owner, String name) {
        return createDocument(owner, folderService.ensureDefault(owner), name);
    }

    // ──────────────────────────── чтение ответов ────────────────────────────

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected MockHttpServletRequestBuilder get(String path) {
        return MockMvcRequestBuilders.get(path);
    }

    protected MockHttpServletRequestBuilder post(String path) {
        return MockMvcRequestBuilders.post(path);
    }

    protected MockHttpServletRequestBuilder put(String path) {
        return MockMvcRequestBuilders.put(path);
    }

    protected MockHttpServletRequestBuilder patch(String path) {
        return MockMvcRequestBuilders.patch(path);
    }

    protected MockHttpServletRequestBuilder delete(String path) {
        return MockMvcRequestBuilders.delete(path);
    }

    /** Тело JSON для запроса. */
    protected String body(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected List<User> allUsers() {
        return userRepository.findAll();
    }
}
