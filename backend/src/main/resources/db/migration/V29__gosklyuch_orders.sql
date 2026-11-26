-- ============================================================
--  Госключ: заявки на подписание документов в мобильном
--  приложении «Госключ» (API ЕПГУ, услуга 10000000374).
--
--  Одна строка = одна отправка документа (группы документов)
--  конкретному подписанту. Внешний идентификатор заявки
--  (orderId) выдаёт ЕПГУ после создания заявки.
-- ============================================================

CREATE TABLE gosklyuch_orders (
    id                  BIGSERIAL PRIMARY KEY,
    document_id         BIGINT       NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    owner_id            BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    signer_snils_enc    TEXT,
    signer_oid          VARCHAR(20),
    external_order_id   BIGINT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    status_code         INTEGER,
    status_name         VARCHAR(100),
    error               VARCHAR(500),
    signature_base64    TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

COMMENT ON TABLE gosklyuch_orders IS 'Заявки на подписание документов в МП «Госключ»';
COMMENT ON COLUMN gosklyuch_orders.signer_snils_enc IS 'СНИЛС подписанта (ФЛ), зашифрован AES-GCM';
COMMENT ON COLUMN gosklyuch_orders.signer_oid IS 'OID учётной записи ЕСИА (альтернатива СНИЛС)';
COMMENT ON COLUMN gosklyuch_orders.external_order_id IS 'orderId, выданный ЕПГУ';
COMMENT ON COLUMN gosklyuch_orders.status IS 'NEW | SENT | SIGNED | REJECTED | EXPIRED | ERROR';
COMMENT ON COLUMN gosklyuch_orders.status_code IS 'Код статуса из API ЕПГУ';
COMMENT ON COLUMN gosklyuch_orders.signature_base64 IS 'Полученная от Госключа подпись (base64 PKCS#7)';
COMMENT ON COLUMN gosklyuch_orders.completed_at IS 'Когда заявка пришла в терминальный статус';

CREATE INDEX IF NOT EXISTS idx_gosklyuch_orders_document ON gosklyuch_orders (document_id);
CREATE INDEX IF NOT EXISTS idx_gosklyuch_orders_owner ON gosklyuch_orders (owner_id);
CREATE INDEX IF NOT EXISTS idx_gosklyuch_orders_status ON gosklyuch_orders (status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_gosklyuch_orders_external
    ON gosklyuch_orders (external_order_id)
    WHERE external_order_id IS NOT NULL;
