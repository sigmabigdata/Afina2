package ru.afina.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import ru.afina.entity.GosklyuchOrder;
import ru.afina.gosklyuch.GosklyuchStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GosklyuchOrderRepository extends JpaRepository<GosklyuchOrder, Long> {

    List<GosklyuchOrder> findByDocumentIdOrderByIdDesc(Long documentId);

    Optional<GosklyuchOrder> findTopByDocumentIdOrderByIdDesc(Long documentId);

    Optional<GosklyuchOrder> findByExternalOrderId(Long externalOrderId);

    List<GosklyuchOrder> findByStatusIn(Collection<GosklyuchStatus> statuses);

    boolean existsByDocumentIdAndStatusIn(Long documentId, Collection<GosklyuchStatus> statuses);

    /** Последние заявки клиента вместе с документами (для карточки клиента). */
    @Query("select o from GosklyuchOrder o join fetch o.document "
            + "where o.owner.id = :ownerId order by o.id desc")
    List<GosklyuchOrder> findRecentByOwner(@Param("ownerId") Long ownerId, Pageable pageable);

    /** Заявки по списку документов (учитывая пакетные позиции), новые первыми. */
    @Query("select distinct o from GosklyuchOrder o join fetch o.document "
            + "left join fetch o.items i left join fetch i.document "
            + "where o.document.id in :documentIds or i.document.id in :documentIds "
            + "order by o.id desc")
    List<GosklyuchOrder> findByDocumentIds(@Param("documentIds") Collection<Long> documentIds);

    /** Список заявок для админки (с документом и владельцем), с фильтром по статусу. */
    @Query("select distinct o from GosklyuchOrder o join fetch o.document join fetch o.owner "
            + "left join fetch o.items i left join fetch i.document "
            + "where (:status is null or o.status = :status) order by o.id desc")
    List<GosklyuchOrder> findForAdmin(@Param("status") GosklyuchStatus status, Pageable pageable);

    /** Одна заявка с документом и владельцем (для ручного применения подписи). */
    @Query("select distinct o from GosklyuchOrder o join fetch o.document join fetch o.owner "
            + "left join fetch o.items i left join fetch i.document where o.id = :id")
    Optional<GosklyuchOrder> findWithDocumentAndOwnerById(@Param("id") Long id);

    /** Незавершённые заявки вместе с документом и владельцем (без N+1 и lazy-ошибок). */
    @Query("select distinct o from GosklyuchOrder o join fetch o.document join fetch o.owner "
            + "left join fetch o.items i left join fetch i.document "
            + "where o.status in :statuses")
    List<GosklyuchOrder> findPendingWithDocument(
            @Param("statuses") Collection<GosklyuchStatus> statuses);

    /** Есть ли незавершённая заявка по документу (включая пакетные позиции). */
    @Query("select count(o) from GosklyuchOrder o left join o.items i "
            + "where (o.document.id = :documentId or i.document.id = :documentId) "
            + "and o.status in :statuses")
    long countPendingForDocument(@Param("documentId") Long documentId,
                                 @Param("statuses") Collection<GosklyuchStatus> statuses);
}
