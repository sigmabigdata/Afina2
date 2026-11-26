package ru.afina.gosklyuch;

/**
 * Демонстрационный подписант пакета: возвращает заглушку вместо PKCS#7.
 *
 * <p>Бин создаётся только при одновременном {@code app.gosklyuch.provider=fake}
 * и {@code app.gosklyuch.signer=fake} (демо-стенд): реальный ЕПГУ в этом режиме
 * не вызывается, архив остаётся внутри приложения. С настоящим провайдером
 * {@code epgu} такой подписант не собирается — приложение не стартует, поэтому
 * отправить незаверенный архив в ЕПГУ невозможно.</p>
 */
public class FakeGosklyuchSigner implements GosklyuchSigner {

    /** Префикс содержимого «подписи» — заметно, что это демо. */
    public static final String PREFIX = "DEMO-GOSKLYUCH-SIGNATURE:";

    @Override
    public byte[] signDetached(String fileName, byte[] content) {
        return (PREFIX + fileName).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
