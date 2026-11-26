package ru.afina.gosklyuch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import ru.afina.gosklyuch.model.GosklyuchAttribute;
import ru.afina.gosklyuch.model.GosklyuchDocument;
import ru.afina.gosklyuch.model.GosklyuchSignRequest;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Госключ: формирование req.xml по XSD v1.8")
class GosklyuchRequestXmlBuilderTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final GosklyuchRequestXmlBuilder builder = new GosklyuchRequestXmlBuilder(MSK);

    @Test
    @DisplayName("УНЭП: пространство имён, порядок и значения элементов")
    void buildsUnepRequest() throws Exception {
        Instant expiration = Instant.parse("2026-05-01T09:30:00Z");
        GosklyuchSignRequest request = new GosklyuchSignRequest("00072972938", null,
                List.of(new GosklyuchDocument("document_1.pdf", "application/pdf",
                        "Согласие", "https://lk.gosuslugi.ru/notifications",
                        expiration,
                        List.of(new GosklyuchAttribute("mnemonics", "TESTMNS")))));

        byte[] xml = builder.build(request, false);
        Document doc = parse(xml);

        assertThat(doc.getDocumentElement().getNamespaceURI()).isEqualTo(GosklyuchRequestXmlBuilder.NS_UNEP);
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("SignRequest");

        NodeList children = doc.getDocumentElement().getChildNodes();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e) {
                order.add(e.getLocalName());
            }
        }
        assertThat(order).containsExactly("Snils", "Document");

        assertThat(text(doc, "Snils")).isEqualTo("000-729-729 38");
        assertThat(text(doc, "DocumentId")).isEqualTo("document_1.pdf");
        assertThat(text(doc, "MimeType")).isEqualTo("application/pdf");
        assertThat(text(doc, "Description")).isEqualTo("Согласие");
        assertThat(text(doc, "AttributeName")).isEqualTo("mnemonics");
        assertThat(text(doc, "AttributeValue")).isEqualTo("TESTMNS");

        ZonedDateTime expected = expiration.atZone(MSK);
        assertThat(text(doc, "SignExpiration"))
                .isEqualTo(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                        .format(expected));
        assertThat(new String(xml, StandardCharsets.UTF_8)).startsWith("<?xml");
    }

    @Test
    @DisplayName("УКЭП: пространство имён urn://…/sign_document_ukep/1.0.0")
    void buildsUkepRequest() throws Exception {
        byte[] xml = builder.build(requestWithDocument(), true);
        Document doc = parse(xml);
        assertThat(doc.getDocumentElement().getNamespaceURI()).isEqualTo(GosklyuchRequestXmlBuilder.NS_UKEP);
    }

    @Test
    @DisplayName("OID добавляется после документов, если задан")
    void appendsOidWhenPresent() throws Exception {
        GosklyuchSignRequest request = new GosklyuchSignRequest("11223344595", "1000550738",
                List.of(new GosklyuchDocument("document_2.xml", "application/xml",
                        null, null, null, List.of())));
        Document doc = parse(builder.build(request, false));
        assertThat(text(doc, "OID")).isEqualTo("1000550738");
        List<String> order = childNames(doc);
        assertThat(order).containsExactly("Snils", "Document", "OID");
    }

    @Test
    @DisplayName("Сформированный req.xml проходит проверку по XSD (Snils + OID)")
    void validAgainstXsd() throws Exception {
        GosklyuchSignRequest request = new GosklyuchSignRequest("11223344595", "1000550738",
                List.of(new GosklyuchDocument("document_2.xml", "application/xml",
                        "Документ", "https://lk.gosuslugi.ru/notifications",
                        Instant.now().plusSeconds(3600),
                        List.of(new GosklyuchAttribute("mnemonics", "MNSV03")))));
        byte[] xml = builder.build(request, false);
        Validator validator = schema().newValidator();
        validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
    }

    @Test
    @DisplayName("Без документов и СНИЛС запрос не формируется")
    void rejectsEmptyRequest() {
        assertThatThrownBy(() -> builder.build(new GosklyuchSignRequest(null, null, List.of()), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("СНИЛС");
        assertThatThrownBy(() -> builder.build(new GosklyuchSignRequest("11223344595", null, List.of()), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("нет документов");
    }

    @Test
    @DisplayName("Проверяются длины полей и количество документов/атрибутов")
    void rejectsTooLongFields() {
        GosklyuchDocument longId = new GosklyuchDocument("x".repeat(51), "application/pdf",
                null, null, null, List.of());
        assertThatThrownBy(() -> builder.build(
                new GosklyuchSignRequest("11223344595", null, List.of(longId)), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DocumentId");

        List<GosklyuchDocument> many = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            many.add(new GosklyuchDocument("document_" + i + ".pdf", "application/pdf",
                    null, null, null, List.of()));
        }
        assertThatThrownBy(() -> builder.build(
                new GosklyuchSignRequest("11223344595", null, many), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("15");

        List<GosklyuchAttribute> attrs = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            attrs.add(new GosklyuchAttribute("a" + i, "v" + i));
        }
        GosklyuchDocument tooManyAttrs = new GosklyuchDocument("document_1.pdf",
                "application/pdf", null, null, null, attrs);
        assertThatThrownBy(() -> builder.build(
                new GosklyuchSignRequest("11223344595", null, List.of(tooManyAttrs)), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("атрибут");
    }

    @Test
    @DisplayName("Значения экранируются (нет XML-инъекции)")
    void escapesValues() throws Exception {
        GosklyuchDocument doc = new GosklyuchDocument("document_1.pdf", "application/pdf",
                "Название <script>&\"'", null, null, List.of());
        Document parsed = parse(builder.build(
                new GosklyuchSignRequest("11223344595", null, List.of(doc)), false));
        assertThat(text(parsed, "Description")).isEqualTo("Название <script>&\"'");
    }

    private static GosklyuchSignRequest requestWithDocument() {
        return new GosklyuchSignRequest("11223344595", null,
                List.of(new GosklyuchDocument("document_1.pdf", "application/pdf",
                        null, null, null, List.of())));
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static List<String> childNames(Document doc) {
        NodeList children = doc.getDocumentElement().getChildNodes();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e) order.add(e.getLocalName());
        }
        return order;
    }

    private static String text(Document doc, String name) {
        NodeList list = doc.getElementsByTagNameNS("*", name);
        assertThat(list.getLength()).isGreaterThan(0);
        return list.item(0).getTextContent();
    }

    private static Schema schema() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try (InputStream in = GosklyuchRequestXmlBuilderTest.class
                .getResourceAsStream("/gosklyuch/sign_document.xsd")) {
            assertThat(in).isNotNull();
            return factory.newSchema(new StreamSource(in));
        }
    }
}
