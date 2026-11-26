package ru.afina.service;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.repository.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private static final String USER_NOT_FOUND = "Пользователь не найден";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserService.class);

    public static final Duration LOGIN_TOKEN_TTL = Duration.ofHours(10);

    private final UserRepository userRepository;
    private final ru.afina.security.PiiEncryptor pii;
    private final SettingsService settings;
    private final ru.afina.repository.DocumentFolderRepository folderRepository;
    private final ru.afina.repository.DocumentRepository documentRepository;
    private final ru.afina.repository.DocumentSignatureRepository signatureRepository;

    public UserService(UserRepository userRepository,
                       ru.afina.security.PiiEncryptor pii,
                       SettingsService settings,
                       ru.afina.repository.DocumentFolderRepository folderRepository,
                       ru.afina.repository.DocumentRepository documentRepository,
                       ru.afina.repository.DocumentSignatureRepository signatureRepository) {
        this.userRepository = userRepository;
        this.pii = pii;
        this.settings = settings;
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.signatureRepository = signatureRepository;
    }

    // ============ Spring Security ============

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findByEmailHash(pii.hash(username))
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден: " + username));
        return org.springframework.security.core.userdetails.User
                .withUsername(u.getEmail())
                .password("{noop}magic")
                .disabled(!u.isEnabled())
                .authorities(List.of(new SimpleGrantedAuthority(u.getRole().name())))
                .build();
    }

    /** Поиск пользователя по email (для персонализации писем). */
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return userRepository.findByEmailHash(pii.hash(email.trim().toLowerCase()));
    }

    // ============ Magic-link для клиентов ============

    @Transactional
    public String generateLoginLink(String email, String baseUrl) {
        Optional<User> opt = userRepository.findByEmailHash(pii.hash(email));
        if (opt.isEmpty()) return null;
        User u = opt.get();
        if (!u.isEnabled()) return null;

        String token = UUID.randomUUID().toString().replace("-", "");
        u.setLoginToken(token);
        int ttlHours = settings.getIntOrDefault("auth.token_ttl_hours", 10);
        u.setLoginTokenExpires(Instant.now().plus(Duration.ofHours(ttlHours)));
        u.setLoginTokenUsedAt(null);
        userRepository.save(u);

        return baseUrl + "/login/confirm?token=" + token;
    }

    @Transactional
    public User consumeLoginToken(String token) {
        if (token == null || token.isBlank()) {
            log.warn("consumeLoginToken: token is null/blank");
            return null;
        }
        Optional<User> opt = userRepository.findByLoginToken(token);
        if (opt.isEmpty()) {
            log.warn("consumeLoginToken: no user with token (len={})", token.length());
            return null;
        }
        User u = opt.get();
        if (u.getLoginTokenExpires() == null || u.getLoginTokenExpires().isBefore(Instant.now())) {
            log.warn("consumeLoginToken: token expired for user id={} (expires={})",
                    u.getId(), u.getLoginTokenExpires());
            return null;
        }

        // Токен действителен до истечения TTL (10 часов).
        // Многоразовый — чтобы Gmail-prefetch не «съедал» единственный клик.
        Instant now = Instant.now();
        if (u.getLoginTokenUsedAt() == null) {
            u.setLoginTokenUsedAt(now);
        }
        u.setLastLoginAt(now);
        log.info("consumeLoginToken: success for user id={} (usedAt={})", u.getId(), u.getLoginTokenUsedAt());
        return userRepository.save(u);
    }

    // ============ Админ по сертификату ============

    @Transactional
    public User upsertAdminByCert(String cn, String snils, Role role) {
        String syntheticEmail = buildAdminEmail(cn, snils);
        String hash = pii.hash(syntheticEmail);
        User u = userRepository.findByEmailHash(hash).orElseGet(User::new);
        u.setEmail(syntheticEmail);
        u.setEmailHash(hash);
        u.setFullName(cn);
        u.setRole(role != null ? role : Role.ROLE_ADMIN);
        u.setEnabled(true);
        u.setLoginToken(null);
        u.setLoginTokenExpires(null);
        return userRepository.save(u);
    }

    /** Старый вызов без роли (совместимость). */
    @Transactional
    public User upsertAdminByCert(String cn, String snils) {
        return upsertAdminByCert(cn, snils, Role.ROLE_ADMIN);
    }

    public static String buildAdminEmail(String cn, String snils) {
        String safeCn = cn == null ? "admin" : cn.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("(^\\.)|(\\.$)", "");
        return safeCn + "+" + snils + "@ukep.local";
    }

    /** Приводит телефон к +7XXXXXXXXXX. Возвращает null, если номер невалидный. */
    /** СНИЛС: только цифры, ровно 11; пустое значение — null, мусор — ошибка. */
    private static String normalizeSnils(String snils) {
        if (snils == null || snils.isBlank()) return null;
        String digits = snils.replaceAll("\\D", "");
        if (digits.length() != 11) {
            throw new IllegalArgumentException("СНИЛС должен содержать 11 цифр");
        }
        return digits;
    }

    private static String normalizePhone(String phone) {
        return ru.afina.util.PersonData.normalizePhone(phone);
    }

    public java.util.Optional<User> findEmployeeByCnAndSnils(String cn, String snils) {
        if (cn == null || cn.isBlank() || snils == null || snils.isBlank()) return java.util.Optional.empty();
        String hash = pii.hash(snils);
        return userRepository.findByFullNameIgnoreCaseAndSnilsHash(cn.trim(), hash)
                .filter(u -> u.getRole() == Role.ROLE_EMPLOYEE || u.getRole() == Role.ROLE_ADMIN);
    }

    // ============ CRUD ============

    @Transactional
    public User adminCreate(String email, String fullName, String phone, String snils, Role role, boolean enabled) {
        if (email == null || email.isBlank())
            throw new IllegalArgumentException("Email обязателен");
        String emailHash = pii.hash(email);
        if (userRepository.existsByEmailHash(emailHash))
            throw new IllegalArgumentException("Email уже занят");

        User u = new User();
        u.setEmail(email.toLowerCase().trim());
        u.setEmailHash(emailHash);
        u.setFullName(fullName == null || fullName.isBlank() ? email : fullName.trim());
        if (phone != null && !phone.isBlank()) {
            String norm = normalizePhone(phone);
            if (norm == null) throw new IllegalArgumentException("Неверный формат телефона");
            u.setPhone(norm);
            u.setPhoneHash(pii.hashPhone(norm));
        }
        String snilsDigits = normalizeSnils(snils);
        if (snilsDigits != null) {
            u.setSnils(snilsDigits);
            u.setSnilsHash(pii.hash(snilsDigits));
        }
        u.setRole(role == null ? Role.ROLE_USER : role);
        u.setEnabled(enabled);
        return userRepository.save(u);
    }

    @Transactional
    public User adminUpdate(Long id, String email, String fullName, String phone, String snils,
                            Role role, boolean enabled) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));

        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(u.getEmail())) {
            String newHash = pii.hash(email);
            if (userRepository.existsByEmailHash(newHash))
                throw new IllegalArgumentException("Email уже занят");
            u.setEmail(email.toLowerCase().trim());
            u.setEmailHash(newHash);
        }
        if (fullName != null && !fullName.isBlank()) u.setFullName(fullName.trim());
        if (phone != null && !phone.isBlank()) {
            String norm = normalizePhone(phone);
            if (norm == null) throw new IllegalArgumentException("Неверный формат телефона");
            u.setPhone(norm);
            u.setPhoneHash(pii.hashPhone(norm));
        } else {
            u.setPhone(null);
            u.setPhoneHash(null);
        }
        String snilsDigits = normalizeSnils(snils);
        if (snilsDigits != null) {
            u.setSnils(snilsDigits);
            u.setSnilsHash(pii.hash(snilsDigits));
        } else if (role == Role.ROLE_USER) {
            u.setSnils(null);
            u.setSnilsHash(null);
        }
        if (role != null) u.setRole(role);
        u.setEnabled(enabled);
        return userRepository.save(u);
    }

    /**
     * Удаление пользователя. currentAdminEmail — email текущего админа,
     * чтобы запретить удаление самого себя.
     */
    /**
     * Применяет необязательные поля профиля клиента (пол, ИНН, паспорт, рождение, адрес).
     * Пустые значения не затирают уже сохранённые — их можно оставить как есть.
     */
    @Transactional
    public void applyClientProfile(Long id, java.util.Map<String, String> f) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));

        if (f.containsKey("sex")) u.setSex(blankToNull(f.get("sex")));
        if (f.containsKey("inn")) {
            String inn = blankToNull(f.get("inn"));
            u.setInn(inn);
            u.setInnHash(inn == null ? null : pii.hash(inn));
        }
        if (f.containsKey("fullName")) {
            String name = ru.afina.util.PersonData.normalizeFullName(f.get("fullName"));
            if (name != null) u.setFullName(name);
        }
        if (f.containsKey("phone")) {
            String raw = blankToNull(f.get("phone"));
            if (raw == null) {
                u.setPhone(null);
                u.setPhoneHash(null);
            } else {
                String norm = normalizePhone(raw);
                if (norm == null) throw new IllegalArgumentException("Неверный формат телефона");
                u.setPhone(norm);
                u.setPhoneHash(pii.hashPhone(norm));
            }
        }
        if (f.containsKey("noPassport")) {
            boolean noPass = "true".equalsIgnoreCase(f.get("noPassport"));
            u.setNoPassport(noPass);
        }
        if (f.containsKey("passportSeries")) u.setPassportSeries(blankToNull(f.get("passportSeries")));
        if (f.containsKey("passportNumber")) u.setPassportNumber(blankToNull(f.get("passportNumber")));
        if (f.containsKey("passportDeptCode")) u.setPassportDeptCode(blankToNull(f.get("passportDeptCode")));
        if (f.containsKey("passportIssuedBy")) u.setPassportIssuedBy(blankToNull(f.get("passportIssuedBy")));
        u.setPassportIssueDate(parseDate(f.get("passportIssueDate"), u.getPassportIssueDate()));
        u.setBirthDate(parseDate(f.get("birthDate"), u.getBirthDate()));
        if (f.containsKey("birthPlace")) u.setBirthPlace(blankToNull(f.get("birthPlace")));
        if (f.containsKey("registrationAddress")) {
            u.setRegistrationAddress(blankToNull(f.get("registrationAddress")));
        }
        userRepository.save(u);
    }

    private static java.time.LocalDate parseDate(String raw, java.time.LocalDate current) {
        if (raw == null) return current;
        String v = raw.trim();
        if (v.isEmpty()) return current;
        try {
            return java.time.LocalDate.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            return current;
        }
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    @Transactional
    public void adminDelete(Long id, String currentAdminEmail) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        if (currentAdminEmail != null && u.getEmail().equalsIgnoreCase(currentAdminEmail)) {
            throw new IllegalArgumentException("Нельзя удалить собственную учётную запись");
        }
        // Документы и подписи удалять каскадно нельзя: это юридически значимые данные.
        if (documentRepository.existsByOwner(u)
                || signatureRepository.existsBySignerUserId(u.getId())) {
            throw new IllegalArgumentException(
                    "У клиента есть документы или подписи. Удаление невозможно — "
                    + "документы должны быть выгружены и удалены по регламенту.");
        }
        // Пустые разделы не являются значимыми — удаляем вместе с клиентом,
        // иначе созданного в админке клиента невозможно удалить.
        folderRepository.deleteAll(folderRepository.findAllByOwnerOrderByCreatedAtDesc(u));
        userRepository.delete(u);
    }

    @Transactional
    public void adminToggle(Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        // Админов не блокируем — у них вход по сертификату, вне сессии их «заблокировать» нельзя
        if (u.getRole() == Role.ROLE_ADMIN) {
            throw new IllegalArgumentException("Нельзя заблокировать администратора");
        }
        u.setEnabled(!u.isEnabled());
        userRepository.save(u);
    }
}
