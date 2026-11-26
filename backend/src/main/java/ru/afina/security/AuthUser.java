package ru.afina.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Аутентифицированный пользователь Afina2.
 *
 * <p>В отличие от стандартного {@code org.springframework.security.core.userdetails.User}
 * несёт идентификатор записи в БД — это избавляет от повторного хэширования e-mail
 * на каждом запросе и корректно работает для администраторов, вошедших по
 * сертификату (у них синтетический e-mail вида {@code cn+снилс@ukep.local}).</p>
 */
public record AuthUser(
        Long id,
        String email,
        String fullName,
        String role) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getPassword() {
        return "{noop}magic";
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
