package ru.afina.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Документ в пакетной заявке Госключа.
 *
 * <p>{@code fileName} — имя файла в архиве заявки; оно же {@code DocumentId}
 * в {@code req.xml}. По нему в ответе ЕПГУ находится подпись
 * {@code <fileName>.sig}.</p>
 */
@Entity
@Table(name = "gosklyuch_order_items")
public class GosklyuchOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private GosklyuchOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "file_name", length = 50)
    private String fileName;

    @Column(name = "signature_base64", columnDefinition = "TEXT")
    private String signatureBase64;

    @Column(name = "applied", nullable = false)
    private boolean applied = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public GosklyuchOrder getOrder() { return order; }
    public void setOrder(GosklyuchOrder order) { this.order = order; }

    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getSignatureBase64() { return signatureBase64; }
    public void setSignatureBase64(String signatureBase64) { this.signatureBase64 = signatureBase64; }

    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }
}
