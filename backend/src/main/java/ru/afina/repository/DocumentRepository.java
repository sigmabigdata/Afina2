package ru.afina.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.User;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    @EntityGraph(attributePaths = {"signatures", "signatures.signerUser", "folder", "owner"})
    List<Document> findAllByOwnerOrderByUploadedAtDesc(User owner);

    Optional<Document> findByIdAndOwner(Long id, User owner);

    boolean existsByOwner(User owner);

    @EntityGraph(attributePaths = {"owner", "signatures", "signatures.signerUser"})
    Optional<Document> findByPublicUuid(java.util.UUID publicUuid);

    /** Для админских операций: загрузить документ с уже инициализированным owner. */
    @EntityGraph(attributePaths = "owner")
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdWithOwner(@Param("id") Long id);

    /**
     * Для визуализации и других операций, которым нужны подписи.
     * Eager-загрузка owner + signatures + signerUser — иначе LazyInitializationException.
     */
    @EntityGraph(attributePaths = {"owner", "folder", "signatures", "signatures.signerUser"})
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdWithSignatures(@Param("id") Long id);

    /** Id документов, у которых есть подписи (для массовой перегенерации визуализаций). */
    @Query("select distinct d.id from Document d join d.signatures s")
    List<Long> findIdsWithSignatures();

    @Query("select d from Document d join fetch d.owner order by d.uploadedAt desc")
    @EntityGraph(attributePaths = {"signatures", "signatures.signerUser"})
    List<Document> findAllWithOwner();

    /**
     * Поиск документов для админки. Pattern (`%значение%`) приходит готовым из контроллера.
     */
    long countBySignedTrue();
    long countBySignedFalse();

    long countByOwner(User owner);
    long countByOwnerAndSignedTrue(User owner);

    // ============ Разделы ============

    @EntityGraph(attributePaths = {"signatures", "signatures.signerUser", "folder", "owner"})
    List<Document> findAllByFolderOrderByUploadedAtDesc(DocumentFolder folder);
    long countByFolderAndSignedTrue(DocumentFolder folder);

    /** Документы клиента без папки (legacy). */
    @Query("select d from Document d where d.owner = :owner and d.folder is null " +
           "order by d.uploadedAt desc")
    List<Document> findUnfiledByOwner(@Param("owner") User owner);
}
