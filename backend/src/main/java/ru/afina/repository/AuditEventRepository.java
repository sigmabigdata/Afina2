package ru.afina.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.afina.entity.AuditEvent;

import java.time.Instant;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

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
     * <p>Каждый фильтр применяется только когда задан: {@code null} в
     * параметре означает «без ограничения». Сортировка — новые события первыми.</p>
     *
     * @param type   точное значение типа события
     * @param result точное значение результата
     * @param actor  шаблон LIKE (нижний регистр) по email субъекта
     * @param from   нижняя граница времени (включительно)
     * @param to     верхняя граница времени (включительно)
     */
    @Query(value = "select e from AuditEvent e "
            + "where (:type is null or e.eventType = :type) "
            + "and (:result is null or e.result = :result) "
            + "and (:actor is null or lower(e.actorEmail) like :actor) "
            + "and (:from is null or e.eventTime >= :from) "
            + "and (:to is null or e.eventTime <= :to) "
            + "order by e.eventTime desc",
            countQuery = "select count(e) from AuditEvent e "
            + "where (:type is null or e.eventType = :type) "
            + "and (:result is null or e.result = :result) "
            + "and (:actor is null or lower(e.actorEmail) like :actor) "
            + "and (:from is null or e.eventTime >= :from) "
            + "and (:to is null or e.eventTime <= :to)")
    Page<AuditEvent> search(@Param("type") String type,
                            @Param("result") String result,
                            @Param("actor") String actor,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);

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
