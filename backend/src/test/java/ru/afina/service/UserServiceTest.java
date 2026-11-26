package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.repository.UserRepository;
import ru.afina.security.PiiEncryptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService: CRUD, magic-link, сертификат-логин")
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PiiEncryptor pii;
    @Mock private SettingsService settings;
    @Mock private ru.afina.repository.DocumentFolderRepository folderRepository;
    @Mock private ru.afina.repository.DocumentRepository documentRepository;
    @Mock private ru.afina.repository.DocumentSignatureRepository signatureRepository;

    @InjectMocks private UserService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setEmail("u@b.ru");
        user.setEmailHash("h");
        user.setFullName("Иванов Иван");
        user.setRole(Role.ROLE_USER);
        user.setEnabled(true);
    }

    // ─── loadUserByUsername ─────────────────────

    @Test
    @DisplayName("loadUserByUsername: not found → UsernameNotFoundException")
    void loadNotFound() {
        when(pii.hash("x@b.ru")).thenReturn("hx");
        when(userRepository.findByEmailHash("hx")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("x@b.ru"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("loadUserByUsername: found → UserDetails с ролью")
    void loadFound() {
        when(pii.hash("u@b.ru")).thenReturn("h");
        when(userRepository.findByEmailHash("h")).thenReturn(Optional.of(user));

        UserDetails ud = service.loadUserByUsername("u@b.ru");
        assertThat(ud.getUsername()).isEqualTo("u@b.ru");
        assertThat(ud.isEnabled()).isTrue();
        assertThat(ud.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("loadUserByUsername: disabled user → UserDetails.isEnabled=false")
    void loadDisabled() {
        user.setEnabled(false);
        when(pii.hash("u@b.ru")).thenReturn("h");
        when(userRepository.findByEmailHash("h")).thenReturn(Optional.of(user));

        assertThat(service.loadUserByUsername("u@b.ru").isEnabled()).isFalse();
    }

    // ─── generateLoginLink ─────────────────────

    @Test
    @DisplayName("generateLoginLink: пользователь не найден → null")
    void generateNotFound() {
        when(pii.hash("x@b.ru")).thenReturn("hx");
        when(userRepository.findByEmailHash("hx")).thenReturn(Optional.empty());
        assertThat(service.generateLoginLink("x@b.ru", "https://afina")).isNull();
    }

    @Test
    @DisplayName("generateLoginLink: disabled → null")
    void generateDisabled() {
        user.setEnabled(false);
        when(pii.hash("u@b.ru")).thenReturn("h");
        when(userRepository.findByEmailHash("h")).thenReturn(Optional.of(user));
        assertThat(service.generateLoginLink("u@b.ru", "https://afina")).isNull();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("generateLoginLink: ok → сохранён токен, возвращён URL")
    void generateOk() {
        when(pii.hash("u@b.ru")).thenReturn("h");
        when(userRepository.findByEmailHash("h")).thenReturn(Optional.of(user));
        when(settings.getIntOrDefault("auth.token_ttl_hours", 10)).thenReturn(10);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        String url = service.generateLoginLink("u@b.ru", "https://afina");

        assertThat(url).startsWith("https://afina/login/confirm?token=");
        assertThat(user.getLoginToken()).isNotBlank();
        assertThat(user.getLoginTokenExpires()).isAfter(Instant.now());
        assertThat(user.getLoginTokenUsedAt()).isNull();
    }

    // ─── consumeLoginToken ─────────────────────

    @Test
    @DisplayName("consumeLoginToken: null/blank → null")
    void consumeBlank() {
        assertThat(service.consumeLoginToken(null)).isNull();
        assertThat(service.consumeLoginToken("")).isNull();
        assertThat(service.consumeLoginToken("  ")).isNull();
    }

    @Test
    @DisplayName("consumeLoginToken: не найден → null")
    void consumeNotFound() {
        when(userRepository.findByLoginToken("t")).thenReturn(Optional.empty());
        assertThat(service.consumeLoginToken("t")).isNull();
    }

    @Test
    @DisplayName("consumeLoginToken: expired → null")
    void consumeExpired() {
        user.setLoginTokenExpires(Instant.now().minusSeconds(60));
        when(userRepository.findByLoginToken("t")).thenReturn(Optional.of(user));
        assertThat(service.consumeLoginToken("t")).isNull();
    }

    @Test
    @DisplayName("consumeLoginToken: без expires → null")
    void consumeNoExpires() {
        user.setLoginTokenExpires(null);
        when(userRepository.findByLoginToken("t")).thenReturn(Optional.of(user));
        assertThat(service.consumeLoginToken("t")).isNull();
    }

    @Test
    @DisplayName("consumeLoginToken: ok → возвращает User, usedAt=now, lastLoginAt=now")
    void consumeOkFirstUse() {
        user.setLoginTokenExpires(Instant.now().plusSeconds(3600));
        when(userRepository.findByLoginToken("t")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        User u = service.consumeLoginToken("t");
        assertThat(u).isNotNull();
        assertThat(u.getLoginTokenUsedAt()).isNotNull();
        assertThat(u.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("consumeLoginToken: повторный вызов → usedAt НЕ перезаписывается")
    void consumeReuseKeepsUsedAt() {
        Instant first = Instant.now().minusSeconds(60);
        user.setLoginTokenExpires(Instant.now().plusSeconds(3600));
        user.setLoginTokenUsedAt(first);
        when(userRepository.findByLoginToken("t")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.consumeLoginToken("t");
        assertThat(user.getLoginTokenUsedAt()).isEqualTo(first);
    }

    // ─── buildAdminEmail (static) ──────────────

    @Test
    @DisplayName("buildAdminEmail: транслитерация в lower + +snils@ukep.local")
    void buildEmailRu() {
        String e = UserService.buildAdminEmail("Иванов Иван", "12345678901");
        assertThat(e).endsWith("+12345678901@ukep.local").doesNotContain(" ");
    }

    @Test
    @DisplayName("buildAdminEmail: null CN → admin+snils")
    void buildEmailNullCn() {
        assertThat(UserService.buildAdminEmail(null, "123"))
                .isEqualTo("admin+123@ukep.local");
    }

    // ─── findEmployeeByCnAndSnils ──────────────

    @Test
    @DisplayName("findEmployee: blank cn/snils → empty")
    void findEmployeeBlank() {
        assertThat(service.findEmployeeByCnAndSnils(null, "123")).isEmpty();
        assertThat(service.findEmployeeByCnAndSnils("CN", null)).isEmpty();
    }

    @Test
    @DisplayName("findEmployee: ROLE_USER → empty (не сотрудник)")
    void findEmployeeNotEmployee() {
        when(pii.hash("s")).thenReturn("hs");
        when(userRepository.findByFullNameIgnoreCaseAndSnilsHash("CN", "hs"))
                .thenReturn(Optional.of(user));
        assertThat(service.findEmployeeByCnAndSnils("CN", "s")).isEmpty();
    }

    @Test
    @DisplayName("findEmployee: ROLE_EMPLOYEE → найден")
    void findEmployeeOk() {
        user.setRole(Role.ROLE_EMPLOYEE);
        when(pii.hash("s")).thenReturn("hs");
        when(userRepository.findByFullNameIgnoreCaseAndSnilsHash("CN", "hs"))
                .thenReturn(Optional.of(user));
        assertThat(service.findEmployeeByCnAndSnils("CN", "s")).contains(user);
    }

    // ─── adminCreate ───────────────────────────

    @Test
    @DisplayName("adminCreate: пустой email → IAE")
    void adminCreateBlankEmail() {
        assertThatThrownBy(() -> service.adminCreate("", "Иванов", null, null, Role.ROLE_USER, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email");
    }

    @Test
    @DisplayName("adminCreate: email занят → IAE")
    void adminCreateEmailTaken() {
        when(pii.hash("u@b.ru")).thenReturn("h");
        when(userRepository.existsByEmailHash("h")).thenReturn(true);

        assertThatThrownBy(() -> service.adminCreate("u@b.ru", "Иванов", null, null, Role.ROLE_USER, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже занят");
    }

    @Test
    @DisplayName("adminCreate: без phone → пользователь сохранён")
    void adminCreateNoPhone() {
        when(pii.hash("new@b.ru")).thenReturn("hn");
        when(userRepository.existsByEmailHash("hn")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        User u = service.adminCreate("new@b.ru", "Петров", null, null, Role.ROLE_USER, true);
        assertThat(u.getPhone()).isNull();
        assertThat(u.getEmail()).isEqualTo("new@b.ru");
    }

    @Test
    @DisplayName("adminCreate: плохой phone → IAE")
    void adminCreateBadPhone() {
        when(pii.hash("new@b.ru")).thenReturn("hn");
        when(userRepository.existsByEmailHash("hn")).thenReturn(false);

        assertThatThrownBy(() -> service.adminCreate("new@b.ru", "П", "abc", null, Role.ROLE_USER, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("телефон");
    }

    @Test
    @DisplayName("adminCreate: хороший phone → нормализуется в +7...")
    void adminCreateNormalizesPhone() {
        when(pii.hash("new@b.ru")).thenReturn("hn");
        when(userRepository.existsByEmailHash("hn")).thenReturn(false);
        when(pii.hashPhone("+79991234567")).thenReturn("hp");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        User u = service.adminCreate("new@b.ru", "П", "89991234567", null, Role.ROLE_USER, true);
        assertThat(u.getPhone()).isEqualTo("+79991234567");
        assertThat(u.getPhoneHash()).isEqualTo("hp");
    }

    @Test
    @DisplayName("adminCreate: с snils → snils и snilsHash")
    void adminCreateWithSnils() {
        when(pii.hash("new@b.ru")).thenReturn("hn");
        when(userRepository.existsByEmailHash("hn")).thenReturn(false);
        when(pii.hash("12345678901")).thenReturn("hs");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        User u = service.adminCreate("new@b.ru", "П", null, "12345678901", Role.ROLE_EMPLOYEE, true);
        assertThat(u.getSnils()).isEqualTo("12345678901");
        assertThat(u.getSnilsHash()).isEqualTo("hs");
        assertThat(u.getRole()).isEqualTo(Role.ROLE_EMPLOYEE);
    }

    // ─── adminUpdate ───────────────────────────

    @Test
    @DisplayName("adminUpdate: not found → IAE")
    void adminUpdateNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminUpdate(99L, "e@b.ru", "Ф", null, null, Role.ROLE_USER, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("adminUpdate: новый email занят → IAE")
    void adminUpdateEmailTaken() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(pii.hash("new@b.ru")).thenReturn("hn");
        when(userRepository.existsByEmailHash("hn")).thenReturn(true);

        assertThatThrownBy(() -> service.adminUpdate(1L, "new@b.ru", null, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже занят");
    }

    @Test
    @DisplayName("adminUpdate: phone=null → старый phone обнуляется")
    void adminUpdateClearsPhone() {
        user.setPhone("+79991234567");
        user.setPhoneHash("hp");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.adminUpdate(1L, null, null, null, null, null, true);
        assertThat(user.getPhone()).isNull();
        assertThat(user.getPhoneHash()).isNull();
    }

    @Test
    @DisplayName("adminUpdate: role=ROLE_USER + snils=null → snils обнуляется")
    void adminUpdateClearsSnilsForUser() {
        user.setSnils("old");
        user.setSnilsHash("oldh");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.adminUpdate(1L, null, null, null, null, Role.ROLE_USER, true);
        assertThat(user.getSnils()).isNull();
        assertThat(user.getSnilsHash()).isNull();
    }

    // ─── adminDelete ───────────────────────────

    @Test
    @DisplayName("adminDelete: not found → IAE")
    void adminDeleteNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminDelete(99L, "admin@b.ru"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("adminDelete: self-delete → IAE")
    void adminDeleteSelf() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.adminDelete(1L, "U@B.RU"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("собственную");
    }

    @Test
    @DisplayName("adminDelete: ok → delete")
    void adminDeleteOk() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(documentRepository.existsByOwner(user)).thenReturn(false);
        when(signatureRepository.existsBySignerUserId(1L)).thenReturn(false);
        service.adminDelete(1L, "other@b.ru");
        verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("adminDelete: у клиента есть документы → понятная ошибка, не FK-500")
    void adminDeleteWithData() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(documentRepository.existsByOwner(user)).thenReturn(true);

        assertThatThrownBy(() -> service.adminDelete(1L, "other@b.ru"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("документы");
        verify(userRepository, org.mockito.Mockito.never()).delete(user);
    }

    // ─── adminToggle ───────────────────────────

    @Test
    @DisplayName("adminToggle: admin → IAE")
    void adminToggleAdmin() {
        user.setRole(Role.ROLE_ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.adminToggle(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("администратора");
    }

    @Test
    @DisplayName("adminToggle: user → инвертирует enabled")
    void adminToggleUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.adminToggle(1L);
        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("adminUpdate: СНИЛС клиента сохраняется и хешируется")
    void adminUpdateClientSnils() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(pii.hash("12345678901")).thenReturn("snils-hash");

        service.adminUpdate(1L, "u@b.ru", "Иванов Иван", "+79001112233",
                "123 456 789 01", Role.ROLE_USER, true);

        assertThat(user.getSnils()).isEqualTo("12345678901");
        assertThat(user.getSnilsHash()).isEqualTo("snils-hash");
    }

    @Test
    @DisplayName("adminUpdate: некорректный СНИЛС отклоняется")
    void adminUpdateBadSnils() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.adminUpdate(1L, "u@b.ru", "Иванов Иван",
                "+79001112233", "12345", Role.ROLE_USER, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("СНИЛС должен содержать 11 цифр");
    }

    // ─── applyClientProfile ────────────────────

    @Test
    @DisplayName("applyClientProfile: паспорт, ИНН с хешем, даты и пустые значения")
    void applyClientProfileFull() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(pii.hash("7700000001")).thenReturn("inn-hash");

        service.applyClientProfile(1L, java.util.Map.ofEntries(
                java.util.Map.entry("sex", "M"),
                java.util.Map.entry("inn", "7700000001"),
                java.util.Map.entry("noPassport", "true"),
                java.util.Map.entry("passportSeries", "1234"),
                java.util.Map.entry("passportNumber", "567890"),
                java.util.Map.entry("passportDeptCode", "770-001"),
                java.util.Map.entry("passportIssuedBy", "ОВД Арбат"),
                java.util.Map.entry("passportIssueDate", "2019-03-15"),
                java.util.Map.entry("birthDate", "1985-08-07"),
                java.util.Map.entry("birthPlace", "Москва"),
                java.util.Map.entry("registrationAddress", "Москва, Тверская, 1")
        ));

        assertThat(user.getSex()).isEqualTo("M");
        assertThat(user.getInn()).isEqualTo("7700000001");
        assertThat(user.getInnHash()).isEqualTo("inn-hash");
        assertThat(user.isNoPassport()).isTrue();
        assertThat(user.getPassportSeries()).isEqualTo("1234");
        assertThat(user.getPassportNumber()).isEqualTo("567890");
        assertThat(user.getPassportDeptCode()).isEqualTo("770-001");
        assertThat(user.getPassportIssuedBy()).isEqualTo("ОВД Арбат");
        assertThat(user.getPassportIssueDate()).isEqualTo(java.time.LocalDate.of(2019, 3, 15));
        assertThat(user.getBirthDate()).isEqualTo(java.time.LocalDate.of(1985, 8, 7));
        assertThat(user.getBirthPlace()).isEqualTo("Москва");
        assertThat(user.getRegistrationAddress()).isEqualTo("Москва, Тверская, 1");
    }

    @Test
    @DisplayName("applyClientProfile: пустые строки → null, некорректная дата сохраняет прежнюю")
    void applyClientProfileBlanks() {
        user.setBirthDate(java.time.LocalDate.of(1985, 8, 7));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.applyClientProfile(1L, java.util.Map.of(
                "sex", "  ",
                "inn", "",
                "birthPlace", " ",
                "passportSeries", "",
                "birthDate", "07.08.1985",
                "passportIssueDate", ""
        ));

        assertThat(user.getSex()).isNull();
        assertThat(user.getInn()).isNull();
        assertThat(user.getInnHash()).isNull();
        assertThat(user.getBirthPlace()).isNull();
        assertThat(user.getPassportSeries()).isNull();
        assertThat(user.getBirthDate()).isEqualTo(java.time.LocalDate.of(1985, 8, 7));
    }

    @Test
    @DisplayName("applyClientProfile: ФИО приводится к «Фамилия Имя Отчество»")
    void applyClientProfileFullName() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.applyClientProfile(1L, java.util.Map.of("fullName", "иванов иван иванович"));

        assertThat(user.getFullName()).isEqualTo("Иванов Иван Иванович");
    }

    @Test
    @DisplayName("applyClientProfile: некорректное ФИО не перетирает карточку")
    void applyClientProfileBadFullName() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.applyClientProfile(1L, java.util.Map.of("fullName", "Иванов"));

        assertThat(user.getFullName()).isEqualTo("Иванов Иван");
    }

    @Test
    @DisplayName("applyClientProfile: неизвестный пользователь → IAE")
    void applyClientProfileNotFound() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyClientProfile(9L, java.util.Map.of("sex", "M")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
