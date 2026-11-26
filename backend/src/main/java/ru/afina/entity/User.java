package ru.afina.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // email: в БД хранится в email_enc (AES-GCM), прозрачно шифруется через @Convert
    @Column(name = "email_enc", length = 500)
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String email;

    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Column(nullable = false)
    private String fullName;

    @Column(name = "phone_enc", length = 500)
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String phone;

    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    @Column(name = "snils_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String snils;

    @Column(name = "snils_hash", length = 64)
    private String snilsHash;


    // ─── Профиль клиента (персональные данные) ─────────────────
    // PII-поля хранятся в *_enc (AES-GCM) через PiiStringConverter.
    @Column(length = 10)
    private String sex;

    @Column(name = "inn_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String inn;

    @Column(name = "inn_hash", length = 64)
    private String innHash;

    @Column(name = "passport_series_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String passportSeries;

    @Column(name = "passport_number_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String passportNumber;

    @Column(name = "passport_dept_code", length = 20)
    private String passportDeptCode;

    @Column(name = "passport_issue_date")
    private java.time.LocalDate passportIssueDate;

    @Column(name = "passport_issued_by_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String passportIssuedBy;

    @Column(name = "birth_date")
    private java.time.LocalDate birthDate;

    @Column(name = "birth_place_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String birthPlace;

    @Column(name = "registration_address_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.afina.security.PiiStringConverter.class)
    private String registrationAddress;

    @Column(name = "no_passport", nullable = false)
    private boolean noPassport = false;

    /** Когда клиент сам ввёл ФИО при первом входе (оно перекрывает введённое админом). */
    @Column(name = "full_name_confirmed_at")
    private Instant fullNameConfirmedAt;

    /** Когда подписано согласие на обработку персональных данных. */
    @Column(name = "consent_signed_at")
    private Instant consentSignedAt;

    /** Документ согласия в разделе «Согласия». */
    @Column(name = "consent_document_id")
    private Long consentDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.ROLE_USER;

    @Column(nullable = false)
    private boolean enabled = true;

    private String loginToken;
    private Instant loginTokenExpires;
    private Instant loginTokenUsedAt;
    private Instant lastLoginAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Document> documents = new ArrayList<>();

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getEmailHash() { return emailHash; }
    public String getPhoneHash() { return phoneHash; }
    public String getSnils() { return snils; }
    public String getSnilsHash() { return snilsHash; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public String getLoginToken() { return loginToken; }
    public Instant getLoginTokenExpires() { return loginTokenExpires; }
    public Instant getLoginTokenUsedAt() { return loginTokenUsedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Document> getDocuments() { return documents; }

    public void setId(Long id) { this.id = id; }
    public void setEmail(String email) { this.email = email; }
    public void setEmailHash(String emailHash) { this.emailHash = emailHash; }
    public void setPhoneHash(String phoneHash) { this.phoneHash = phoneHash; }
    public void setSnils(String snils) { this.snils = snils; }
    public void setSnilsHash(String snilsHash) { this.snilsHash = snilsHash; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setRole(Role role) { this.role = role; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setLoginToken(String loginToken) { this.loginToken = loginToken; }
    public void setLoginTokenExpires(Instant loginTokenExpires) { this.loginTokenExpires = loginTokenExpires; }
    public void setLoginTokenUsedAt(Instant loginTokenUsedAt) { this.loginTokenUsedAt = loginTokenUsedAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getSex() { return sex; }
    public String getInn() { return inn; }
    public String getInnHash() { return innHash; }
    public String getPassportSeries() { return passportSeries; }
    public String getPassportNumber() { return passportNumber; }
    public String getPassportDeptCode() { return passportDeptCode; }
    public java.time.LocalDate getPassportIssueDate() { return passportIssueDate; }
    public String getPassportIssuedBy() { return passportIssuedBy; }
    public java.time.LocalDate getBirthDate() { return birthDate; }
    public String getBirthPlace() { return birthPlace; }
    public String getRegistrationAddress() { return registrationAddress; }
    public boolean isNoPassport() { return noPassport; }
    public Instant getFullNameConfirmedAt() { return fullNameConfirmedAt; }
    public Instant getConsentSignedAt() { return consentSignedAt; }
    public Long getConsentDocumentId() { return consentDocumentId; }

    public void setSex(String sex) { this.sex = sex; }
    public void setInn(String inn) { this.inn = inn; }
    public void setInnHash(String innHash) { this.innHash = innHash; }
    public void setPassportSeries(String passportSeries) { this.passportSeries = passportSeries; }
    public void setPassportNumber(String passportNumber) { this.passportNumber = passportNumber; }
    public void setPassportDeptCode(String passportDeptCode) { this.passportDeptCode = passportDeptCode; }
    public void setPassportIssueDate(java.time.LocalDate passportIssueDate) { this.passportIssueDate = passportIssueDate; }
    public void setPassportIssuedBy(String passportIssuedBy) { this.passportIssuedBy = passportIssuedBy; }
    public void setBirthDate(java.time.LocalDate birthDate) { this.birthDate = birthDate; }
    public void setBirthPlace(String birthPlace) { this.birthPlace = birthPlace; }
    public void setRegistrationAddress(String registrationAddress) { this.registrationAddress = registrationAddress; }
    public void setNoPassport(boolean noPassport) { this.noPassport = noPassport; }
    public void setFullNameConfirmedAt(Instant fullNameConfirmedAt) { this.fullNameConfirmedAt = fullNameConfirmedAt; }
    public void setConsentSignedAt(Instant consentSignedAt) { this.consentSignedAt = consentSignedAt; }
    public void setConsentDocumentId(Long consentDocumentId) { this.consentDocumentId = consentDocumentId; }
    public void setDocuments(List<Document> documents) { this.documents = documents; }
}
