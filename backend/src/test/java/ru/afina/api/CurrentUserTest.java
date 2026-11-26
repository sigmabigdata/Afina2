package ru.afina.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.repository.UserRepository;
import ru.afina.security.AuthUser;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты {@link CurrentUser}: принципал, сущность, проверка администратора.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CurrentUser: доступ к текущему пользователю")
class CurrentUserTest {

    @Mock
    private UserRepository userRepository;

    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        currentUser = new CurrentUser(userRepository);
    }

    // ──────────────────────────── principal ────────────────────────────

    @Test
    @DisplayName("principal(null) → 401")
    void principalNull() {
        assertUnauthorized(() -> currentUser.principal(null));
    }

    @Test
    @DisplayName("principal неаутентифицированного запроса → 401")
    void principalUnauthenticated() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(false);

        assertUnauthorized(() -> currentUser.principal(auth));
    }

    @Test
    @DisplayName("principal с чужим типом принципала → 401")
    void principalWrongType() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "someone", null, List.of());

        assertUnauthorized(() -> currentUser.principal(auth));
    }

    @Test
    @DisplayName("principal → AuthUser из токена")
    void principalOk() {
        AuthUser authUser = authUser(Role.ROLE_USER);

        assertThat(currentUser.principal(authentication(authUser))).isSameAs(authUser);
    }

    // ──────────────────────────── entity ────────────────────────────

    @Test
    @DisplayName("entity: пользователь найден в БД")
    void entityFound() {
        AuthUser authUser = authUser(Role.ROLE_USER);
        User user = user(1L, Role.ROLE_USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThat(currentUser.entity(authentication(authUser))).isSameAs(user);
    }

    @Test
    @DisplayName("entity: пользователь удалён → 401")
    void entityNotFound() {
        AuthUser authUser = authUser(Role.ROLE_USER);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertUnauthorized(() -> currentUser.entity(authentication(authUser)));
    }

    // ──────────────────────────── requireAdmin ────────────────────────────

    @Test
    @DisplayName("requireAdmin: администратор → сущность")
    void requireAdminOk() {
        AuthUser authUser = authUser(Role.ROLE_ADMIN);
        User admin = user(1L, Role.ROLE_ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThat(currentUser.requireAdmin(authentication(authUser))).isSameAs(admin);
        verify(userRepository).findById(1L);
    }

    @Test
    @DisplayName("requireAdmin: клиент → 403 без обращения к БД")
    void requireAdminForbidden() {
        AuthUser authUser = authUser(Role.ROLE_USER);

        assertForbidden(() -> currentUser.requireAdmin(authentication(authUser)));
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("requireAdmin: без аутентификации → 401")
    void requireAdminUnauthorized() {
        assertUnauthorized(() -> currentUser.requireAdmin(null));
    }

    @Test
    @DisplayName("requireAdmin: администратор удалён → 401")
    void requireAdminUserMissing() {
        AuthUser authUser = authUser(Role.ROLE_ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertUnauthorized(() -> currentUser.requireAdmin(authentication(authUser)));
    }

    // ──────────────────────────── isAdmin ────────────────────────────

    @Test
    @DisplayName("isAdmin: true только для ROLE_ADMIN")
    void isAdmin() {
        assertThat(currentUser.isAdmin(authentication(authUser(Role.ROLE_ADMIN)))).isTrue();
        assertThat(currentUser.isAdmin(authentication(authUser(Role.ROLE_USER)))).isFalse();
        assertThat(currentUser.isAdmin(authentication(authUser(Role.ROLE_EMPLOYEE)))).isFalse();
        assertThat(currentUser.isAdmin(null)).isFalse();
        assertThat(currentUser.isAdmin(new UsernamePasswordAuthenticationToken(
                "someone", null, List.of()))).isFalse();
    }

    @Test
    @DisplayName("isAdmin: неаутентифицированный запрос → false")
    void isAdminUnauthenticated() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(false);

        assertThat(currentUser.isAdmin(auth)).isFalse();
    }

    // ──────────────────────────── entityOrNull ────────────────────────────

    @Test
    @DisplayName("entityOrNull: аноним → null")
    void entityOrNullAnonymous() {
        assertThat(currentUser.entityOrNull(null)).isNull();
    }

    @Test
    @DisplayName("entityOrNull: пользователь найден / не найден")
    void entityOrNullLookup() {
        AuthUser authUser = authUser(Role.ROLE_USER);
        User user = user(1L, Role.ROLE_USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThat(currentUser.entityOrNull(authentication(authUser))).isSameAs(user);

        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(currentUser.entityOrNull(authentication(authUser))).isNull();
    }

    // ──────────────────────────── helpers ────────────────────────────

    private static AuthUser authUser(Role role) {
        return new AuthUser(1L, "user@test.local", "Иванов Иван Иванович", role.name());
    }

    private static Authentication authentication(AuthUser authUser) {
        return new UsernamePasswordAuthenticationToken(
                authUser, null, authUser.getAuthorities());
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setEmail("user@test.local");
        user.setFullName("Иванов Иван Иванович");
        user.setRole(role);
        return user;
    }

    private static void assertUnauthorized(Runnable action) {
        assertStatus(action, HttpStatus.UNAUTHORIZED);
    }

    private static void assertForbidden(Runnable action) {
        assertStatus(action, HttpStatus.FORBIDDEN);
    }

    private static void assertStatus(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value())
                                .isEqualTo(expected.value()));
    }
}
