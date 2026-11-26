package ru.afina.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.afina.entity.DocumentSignature;

import java.util.List;

public interface DocumentSignatureRepository extends JpaRepository<DocumentSignature, Long> {

    /** Своя транзакция: вызывается из контроллера без @Transactional (см. apply-poa). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update DocumentSignature s set s.applyPoa = :apply where s.id = :id")
    int updateApplyPoa(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("apply") boolean apply);
    List<DocumentSignature> findAllByDocumentIdOrderBySignedAtAsc(Long documentId);
    long countByDocumentId(Long documentId);
    boolean existsByDocumentIdAndSignerUserId(Long documentId, Long signerUserId);

    boolean existsBySignerUserId(Long signerUserId);
}
