-- ============================================================
--  Госключ: пакетная отправка — несколько документов в одной
--  заявке (спецификация допускает до 15 документов).
--  Существующие заявки получают по одной позиции.
-- ============================================================

CREATE TABLE gosklyuch_order_items (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES gosklyuch_orders (id) ON DELETE CASCADE,
    document_id      BIGINT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    file_name        VARCHAR(50),
    signature_base64 TEXT,
    applied          BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_gosklyuch_items_order_document UNIQUE (order_id, document_id)
);

COMMENT ON TABLE gosklyuch_order_items IS 'Документы, входящие в заявку Госключа';
COMMENT ON COLUMN gosklyuch_order_items.file_name IS 'Имя файла в архиве заявки (DocumentId в req.xml)';
COMMENT ON COLUMN gosklyuch_order_items.signature_base64 IS 'Полученная подпись по этому документу';
COMMENT ON COLUMN gosklyuch_order_items.applied IS 'Подпись применена к документу';

CREATE INDEX idx_gosklyuch_items_order ON gosklyuch_order_items (order_id);
CREATE INDEX idx_gosklyuch_items_document ON gosklyuch_order_items (document_id);

-- Backfill: одна позиция на существующую заявку
INSERT INTO gosklyuch_order_items (order_id, document_id)
SELECT id, document_id FROM gosklyuch_orders
WHERE document_id IS NOT NULL;
