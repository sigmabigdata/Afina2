-- ============================================================
--  Профиль клиента (персональные данные, паспорт) и согласие
--  на обработку персональных данных, подписанное УКЭП.
-- ============================================================

ALTER TABLE users
    ADD COLUMN sex VARCHAR(10),
    ADD COLUMN inn_enc TEXT,
    ADD COLUMN inn_hash VARCHAR(64),
    ADD COLUMN rosreestr_email_enc VARCHAR(500),
    ADD COLUMN passport_series_enc TEXT,
    ADD COLUMN passport_number_enc TEXT,
    ADD COLUMN passport_dept_code VARCHAR(20),
    ADD COLUMN passport_issue_date DATE,
    ADD COLUMN passport_issued_by_enc TEXT,
    ADD COLUMN birth_date DATE,
    ADD COLUMN birth_place_enc TEXT,
    ADD COLUMN registration_address_enc TEXT,
    ADD COLUMN no_passport BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN passport_scan_path VARCHAR(255),
    ADD COLUMN full_name_confirmed_at TIMESTAMPTZ,
    ADD COLUMN consent_signed_at TIMESTAMPTZ,
    ADD COLUMN consent_document_id BIGINT;

COMMENT ON COLUMN users.sex IS 'Пол: M | F (пусто — не указан)';
COMMENT ON COLUMN users.no_passport IS 'Клиент отметил «У меня нет паспорта»';
COMMENT ON COLUMN users.full_name_confirmed_at IS 'Когда клиент сам ввёл ФИО при первом входе';
COMMENT ON COLUMN users.consent_signed_at IS 'Когда подписано согласие на обработку ПД (УКЭП)';
COMMENT ON COLUMN users.consent_document_id IS 'Документ согласия в разделе «Согласия»';

-- Тип шаблона: обычный документ или согласие на обработку ПД
ALTER TABLE document_templates
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'DOCUMENT';

COMMENT ON COLUMN document_templates.kind IS 'DOCUMENT | CONSENT (согласие не видно клиентам в списке шаблонов)';

CREATE INDEX IF NOT EXISTS idx_document_templates_kind ON document_templates (kind);
CREATE INDEX IF NOT EXISTS idx_users_consent_signed ON users (consent_signed_at);
