package ru.afina.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import ru.afina.gosklyuch.GosklyuchStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Заявка на подписание документа (или пакета документов) в МП «Госключ».
 *
 * <p>СНИЛС подписанта — персональные данные, поэтому хранится в
 * {@code signer_snils_enc} через {@link ru.afina.security.PiiStringConverter}.</p>
 */
@Entity
@Table(name = "gosklyuch_orders")
public class GosklyuchOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "signer_snils_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String signerSnils;

    @Column(name = "signer_oid", length = 20)
    private String signerOid;

    @Column(name = "external_order_id")
    private Long externalOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GosklyuchStatus status = GosklyuchStatus.NEW;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "status_name", length = 100)
    private String statusName;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "signature_base64", columnDefinition = "TEXT")
    private String signatureBase64;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GosklyuchOrderItem> items = new ArrayList<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Отметить заявку терминальным статусом и зафиксировать время завершения. */
    public void finish(GosklyuchStatus finalStatus, Instant when) {
        this.status = finalStatus;
        this.completedAt = when;
        this.updatedAt = when;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }

    public String getSignerSnils() { return signerSnils; }
    public void setSignerSnils(String signerSnils) { this.signerSnils = signerSnils; }

    public String getSignerOid() { return signerOid; }
    public void setSignerOid(String signerOid) { this.signerOid = signerOid; }

    public Long getExternalOrderId() { return externalOrderId; }
    public void setExternalOrderId(Long externalOrderId) { this.externalOrderId = externalOrderId; }

    public GosklyuchStatus getStatus() { return status; }
    public void setStatus(GosklyuchStatus status) { this.status = status; }

    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }

    public String getStatusName() { return statusName; }
    public void setStatusName(String statusName) { this.statusName = statusName; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getSignatureBase64() { return signatureBase64; }
    public void setSignatureBase64(String signatureBase64) { this.signatureBase64 = signatureBase64; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public List<GosklyuchOrderItem> getItems() { return items; }
    public void setItems(List<GosklyuchOrderItem> items) { this.items = items; }
}
