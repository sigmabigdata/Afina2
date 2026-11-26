package ru.afina.gosklyuch;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import ru.afina.gosklyuch.model.GosklyuchAttribute;
import ru.afina.gosklyuch.model.GosklyuchDocument;
import ru.afina.gosklyuch.model.GosklyuchSignRequest;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Формирует {@code req.xml} заявки на подписание в МП «Госключ»
 * по XSD из Приложения 1 спецификации API ЕПГУ v1.8 (20.01.2023).
 *
 * <p>Структура (последовательность обязательна):</p>
 * <pre>
 * &lt;ns:SignRequest xmlns:ns="urn://mpkey.gosuslugi.ru/sign_document/1.0.0"&gt;
 *   &lt;ns:Snils&gt;…&lt;/ns:Snils&gt;
 *   &lt;ns:Document&gt;…&lt;/ns:Document&gt;
 *   &lt;ns:OID&gt;…&lt;/ns:OID&gt;
 * &lt;/ns:SignRequest&gt;
 * </pre>
 *
 * <p>Пространство имён УНЭП — {@code urn://mpkey.gosuslugi.ru/sign_document/1.0.0},
 * УКЭП — {@code urn://mpkey.gosuslugi.ru/sign_document_ukep/1.0.0}.</p>
 *
 * <p>Поле {@code OID} включается в запрос только если оно задано: по таблице
 * параметров спецификации подписант идентифицируется СНИЛС <b>или</b> OID.
 * При этом XSD объявляет оба элемента обязательными, поэтому если OID известен,
 * передаются оба поля (в порядке XSD). Вопрос соответствия XSD и таблицы
 * параметров проверяется на тестовом контуре ЕПГУ до включения интеграции.</p>
 */
public class GosklyuchRequestXmlBuilder {

    /** Пространство имён УНЭП. */
    public static final String NS_UNEP = "urn://mpkey.gosuslugi.ru/sign_document/1.0.0";

    /** Пространство имён УКЭП. */
    public static final String NS_UKEP = "urn://mpkey.gosuslugi.ru/sign_document_ukep/1.0.0";

    static final int MAX_DOCUMENTS = 15;
    static final int MAX_ATTRIBUTES = 10;
    static final int MAX_DOCUMENT_ID = 50;
    static final int MAX_MIME_TYPE = 50;
    static final int MAX_DESCRIPTION = 250;
    static final int MAX_BACKLINK = 250;
    static final int MAX_OID = 20;
    static final int MAX_ATTRIBUTE_NAME = 50;

    private static final DateTimeFormatter XSD_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final ZoneId zone;

    public GosklyuchRequestXmlBuilder() {
        this(ZoneId.systemDefault());
    }

    public GosklyuchRequestXmlBuilder(ZoneId zone) {
        this.zone = zone;
    }

    /**
     * Построить {@code req.xml}.
     *
     * @param request заявка
     * @param ukep    {@code true} — пространство имён УКЭП, {@code false} — УНЭП
     * @return содержимое файла в UTF-8
     */
    public byte[] build(GosklyuchSignRequest request, boolean ukep) {
        validate(request);
        String ns = ukep ? NS_UKEP : NS_UNEP;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = dbf.newDocumentBuilder().newDocument();

            Element root = doc.createElementNS(ns, "ns:SignRequest");
            doc.appendChild(root);

            appendText(doc, root, ns, "Snils", GosklyuchSnils.formatted(request.snils()));
            for (GosklyuchDocument item : request.documents()) {
                appendDocument(doc, root, ns, item);
            }
            if (notBlank(request.oid())) {
                appendText(doc, root, ns, "OID", request.oid().trim());
            }

            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сформировать req.xml: " + e.getMessage(), e);
        }
    }

    private void appendDocument(Document doc, Element root, String ns, GosklyuchDocument item) {
        Element el = doc.createElementNS(ns, "ns:Document");
        root.appendChild(el);
        appendText(doc, el, ns, "DocumentId", item.documentId());
        appendText(doc, el, ns, "MimeType", item.mimeType());
        appendIfPresent(doc, el, ns, "Description", item.description());
        appendIfPresent(doc, el, ns, "Backlink", item.backlink());
        if (item.signExpiration() != null) {
            appendText(doc, el, ns, "SignExpiration", formatDateTime(item.signExpiration()));
        }
        for (GosklyuchAttribute attr : item.attributes()) {
            Element a = doc.createElementNS(ns, "ns:Attribute");
            el.appendChild(a);
            appendText(doc, a, ns, "AttributeName", attr.name());
            appendText(doc, a, ns, "AttributeValue", attr.value());
        }
    }

    private static void appendIfPresent(Document doc, Element parent, String ns,
                                       String name, String value) {
        if (notBlank(value)) appendText(doc, parent, ns, name, value.trim());
    }

    private static void appendText(Document doc, Element parent, String ns,
                                   String name, String value) {
        Element el = doc.createElementNS(ns, "ns:" + name);
        el.setTextContent(value == null ? "" : value);
        parent.appendChild(el);
    }

    private String formatDateTime(Instant instant) {
        ZonedDateTime zdt = instant.atZone(zone);
        return XSD_DATE_TIME.format(zdt);
    }

    private static void validate(GosklyuchSignRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Заявка на подписание не задана");
        }
        if (blank(request.snils())) {
            throw new IllegalArgumentException("Не указан СНИЛС подписанта");
        }
        String snils = GosklyuchSnils.digits(request.snils());
        if (snils == null || snils.length() != 11) {
            throw new IllegalArgumentException("СНИЛС должен содержать 11 цифр");
        }
        if (notBlank(request.oid()) && request.oid().trim().length() > MAX_OID) {
            throw new IllegalArgumentException("OID длиннее " + MAX_OID + " символов");
        }
        List<GosklyuchDocument> documents = request.documents();
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("В заявке нет документов");
        }
        if (documents.size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException(
                    "В одной заявке допускается не более " + MAX_DOCUMENTS + " документов");
        }
        for (GosklyuchDocument item : documents) {
            validateDocument(item);
        }
    }

    private static void validateDocument(GosklyuchDocument item) {
        requireLength(item.documentId(), MAX_DOCUMENT_ID, "DocumentId");
        requireLength(item.mimeType(), MAX_MIME_TYPE, "MimeType");
        optionalLength(item.description(), MAX_DESCRIPTION, "Description");
        optionalLength(item.backlink(), MAX_BACKLINK, "Backlink");
        if (item.attributes().size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException(
                    "У документа допускается не более " + MAX_ATTRIBUTES + " атрибутов");
        }
        for (GosklyuchAttribute attr : item.attributes()) {
            requireLength(attr.name(), MAX_ATTRIBUTE_NAME, "AttributeName");
            if (attr.value() == null) {
                throw new IllegalArgumentException("AttributeValue не задан для " + attr.name());
            }
        }
    }

    private static void requireLength(String value, int max, String field) {
        if (blank(value)) {
            throw new IllegalArgumentException("Не заполнено поле " + field);
        }
        if (value.length() > max) {
            throw new IllegalArgumentException("Поле " + field + " длиннее " + max + " символов");
        }
    }

    private static void optionalLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException("Поле " + field + " длиннее " + max + " символов");
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static boolean notBlank(String v) {
        return !blank(v);
    }
}
