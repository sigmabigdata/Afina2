package ru.afina.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.afina.entity.AuditEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findAllByOrderByEventTimeDesc(Pageable pageable);

    long countByEventTypeAndResult(String eventType, String result);

    long countByEventTypeAndEventTimeAfter(String eventType, Instant after);
    long countByEventTypeAndResultAndEventTimeAfter(String eventType, String result, Instant after);

    @Query("select e from AuditEvent e where e.eventType = :type " +
           "and e.result = :result order by e.eventTime desc")
    List<AuditEvent> findRecentByTypeAndResult(@Param("type") String type,
                                                @Param("result") String result,
                                                Pageable pageable);

    /** Количество событий старше cutoff (для UI). */
    long countByEventTimeBefore(Instant cutoff);

    /** Удаление старых событий (retention policy). */
    long deleteByEventTimeBefore(Instant cutoff);

    @Query("select distinct e.eventType from AuditEvent e order by e.eventType")
    List<String> findDistinctEventTypes();

    /** Количество событий с указанным результатом ({@code FAIL} — ошибки). */
    long countByResult(String result);

    /** Количество событий, начиная с указанного момента (сводка «за сегодня»). */
    long countByEventTimeAfter(Instant after);

    /**
     * Постраничный поиск событий журнала с необязательными фильтрами.
     *
     * <p>Реализован через Criteria API, а не через {@code :param is null}:
     * PostgreSQL не может определить тип NULL-параметра в JPQL-выражении
     * {@code (:from is null or ...)}, тогда как Criteria подставляет тип из
     * метамодели. Сортировка — новые события первыми.</p>
     */
    default Page<AuditEvent> search(String type, String result, String actor,
                                    Instant from, Instant to, Pageable pageable) {
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (type != null) {
                predicates.add(cb.equal(root.get("eventType"), type));
            }
            if (result != null) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            if (actor != null) {
                predicates.add(cb.like(cb.lower(root.get("actorEmail")), actor));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventTime"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventTime"), to));
            }
            query.orderBy(cb.desc(root.get("eventTime")));
            return predicates.isEmpty() ? cb.conjunction()
                    : cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return findAll(spec, pageable);
    }

    /** Количество событий по каждому типу (для сводки админки). */
    @Query("select e.eventType as eventType, count(e) as total from AuditEvent e "
            + "group by e.eventType order by count(e) desc")
    List<TypeCount> countByType();

    /** Агрегат «тип события → количество событий». */
    interface TypeCount {

        String getEventType();

        long getTotal();
    }
}
