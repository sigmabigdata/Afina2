package ru.afina.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.afina.entity.Role;
import ru.afina.entity.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public interface UserRepository
        extends JpaRepository<User, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<User> {

    // === Поиск по hash (email/phone шифруются, hash для exact-поиска) ===

    Optional<User> findByEmailHash(String emailHash);
    Optional<User> findByPhoneHash(String phoneHash);
    Optional<User> findByFullNameIgnoreCaseAndSnilsHash(String fullName, String snilsHash);
    boolean existsByEmailHash(String emailHash);
    Optional<User> findByLoginToken(String loginToken);

    List<User> findAllByOrderByCreatedAtDesc();
    long countByEnabledTrue();
    long countByEnabledFalse();

    /**
     * Поиск для админки (Criteria API — PostgreSQL не умеет выводить тип
     * NULL-параметра в JPQL-конструкции {@code (:param is null or ...)}).
     *
     * @param enabled   фильтр по активности ({@code null} — все)
     * @param role      фильтр по роли ({@code null} — все)
     * @param q         шаблон LIKE в нижнем регистре по ФИО ({@code null} — без поиска)
     * @param emailHash точный хэш e-mail ({@code null} — без поиска по e-mail)
     */
    default List<User> searchForAdmin(Boolean enabled, Role role, String q, String emailHash) {
        org.springframework.data.jpa.domain.Specification<User> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (q != null || emailHash != null) {
                List<jakarta.persistence.criteria.Predicate> any = new ArrayList<>();
                if (q != null) {
                    any.add(cb.like(cb.lower(root.get("fullName")), q));
                }
                if (emailHash != null) {
                    any.add(cb.equal(root.get("emailHash"), emailHash));
                }
                predicates.add(cb.or(any.toArray(
                        new jakarta.persistence.criteria.Predicate[0])));
            }
            query.orderBy(cb.desc(root.get("createdAt")));
            return predicates.isEmpty() ? cb.conjunction()
                    : cb.and(predicates.toArray(
                            new jakarta.persistence.criteria.Predicate[0]));
        };
        return findAll(spec);
    }

}
