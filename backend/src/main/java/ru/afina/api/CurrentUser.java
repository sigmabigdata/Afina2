package ru.afina.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.User;
import ru.afina.repository.UserRepository;
import ru.afina.security.AuthUser;

/**
 * Доступ к текущему пользователю из REST-контроллеров.
 *
 * <p>Принципал — {@link AuthUser} (см. {@code SecurityConfig}), поэтому
 * обращение к БД нужно только когда требуется сущность целиком.</p>
 */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Принципал или 401, если запрос анонимный. */
    public AuthUser principal(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthUser au)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход");
        }
        return au;
    }

    /** Сущность текущего пользователя (свежая из БД). */
    public User entity(Authentication auth) {
        return load(principal(auth));
    }

    public User load(AuthUser au) {
        return userRepository.findById(au.id())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
    }

    public User requireAdmin(Authentication auth) {
        AuthUser au = principal(auth);
        if (!"ROLE_ADMIN".equals(au.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Действие доступно только администратору");
        }
        return load(au);
    }

    public boolean isAdmin(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof AuthUser au
                && "ROLE_ADMIN".equals(au.role());
    }

    /** Текущий пользователь или {@code null}, если запрос анонимный. */
    public User entityOrNull(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthUser au)) {
            return null;
        }
        return userRepository.findById(au.id()).orElse(null);
    }
}
