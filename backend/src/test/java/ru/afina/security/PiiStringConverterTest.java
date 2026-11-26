package ru.afina.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PiiStringConverter: JPA AttributeConverter → PiiEncryptor")
class PiiStringConverterTest {

    @Mock
    private PiiEncryptor encryptor;

    private PiiStringConverter converter;

    @BeforeEach
    void setUp() {
        converter = new PiiStringConverter(encryptor);
    }

    @Test
    @DisplayName("convertToDatabaseColumn: null → null, encryptor не тронут")
    void toDbNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        verifyNoInteractions(encryptor);
    }

    @Test
    @DisplayName("convertToDatabaseColumn: делегирует encrypt")
    void toDbEncrypt() {
        when(encryptor.encrypt("user@b.ru")).thenReturn("v1:xxx");
        assertThat(converter.convertToDatabaseColumn("user@b.ru")).isEqualTo("v1:xxx");
        verify(encryptor).encrypt("user@b.ru");
    }

    @Test
    @DisplayName("convertToEntityAttribute: null → null, encryptor не тронут")
    void toEntityNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        verifyNoInteractions(encryptor);
    }

    @Test
    @DisplayName("convertToEntityAttribute: делегирует decrypt")
    void toEntityDecrypt() {
        when(encryptor.decrypt("v1:xxx")).thenReturn("user@b.ru");
        assertThat(converter.convertToEntityAttribute("v1:xxx")).isEqualTo("user@b.ru");
        verify(encryptor).decrypt("v1:xxx");
    }

    @Test
    @DisplayName("round-trip с реальным PiiEncryptor (@TempDir)")
    void roundtripWithRealEncryptor(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve("pii.key.v1"),
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        java.nio.file.Files.writeString(dir.resolve("pii.key.current"), "1");

        PiiEncryptor real = new PiiEncryptor(dir.resolve("legacy").toString(), dir.toString());
        PiiStringConverter c = new PiiStringConverter(real);

        String ct = c.convertToDatabaseColumn("secret@b.ru");
        assertThat(ct).startsWith("v1:").isNotEqualTo("secret@b.ru");
        assertThat(c.convertToEntityAttribute(ct)).isEqualTo("secret@b.ru");
    }
}
