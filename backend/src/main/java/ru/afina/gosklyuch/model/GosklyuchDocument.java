package ru.afina.gosklyuch.model;

import java.time.Instant;
import java.util.List;

/**
 * Описание одного документа в заявке Госключа (элемент {@code Document} XSD).
 *
 * @param documentId      идентификатор документа, до 50 символов; должен совпадать
 *                        с именем файла в архиве заявки
 * @param mimeType        MIME-тип содержимого, например {@code application/pdf}
 * @param description     название пакета документов для подписанта, до 250 символов
 * @param backlink        ссылка возврата после подписания, до 250 символов
 * @param signExpiration  срок подписания (не более 24 часов с момента отправки)
 * @param attributes      дополнительные атрибуты (не более 10)
 */
public record GosklyuchDocument(String documentId,
                                String mimeType,
                                String description,
                                String backlink,
                                Instant signExpiration,
                                List<GosklyuchAttribute> attributes) {

    public GosklyuchDocument {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }
}
