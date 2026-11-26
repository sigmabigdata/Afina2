package ru.afina.gosklyuch.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Тестовый/демонстрационный клиент Госключа без внешних вызовов.
 *
 * <p>Включается только явной настройкой {@code app.gosklyuch.provider=fake}
 * (например, в демо-стенде или интеграционных тестах) и имитирует
 * полный цикл: создание заявки, загрузку архива, статус «подписано»,
 * скачивание файла подписи. Файл {@code <имя>.sig} содержит заглушку,
 * а не настоящий PKCS#7, поэтому автоматическое применение подписи
 * к документу (флаг {@code app.gosklyuch.apply-signature}) с этим
 * клиентом не пройдёт проверку — это ожидаемо.</p>
 */
public class FakeGosklyuchClient implements GosklyuchClient {

    /** Префикс содержимого файлов подписи, которые возвращает заглушка. */
    public static final String SIGNATURE_PREFIX = "FAKE-GOSKLYUCH-SIG:";

    private final AtomicLong ids = new AtomicLong(1_000_000);
    private final Map<Long, byte[]> packages = new ConcurrentHashMap<>();
    private final Map<Long, Integer> statuses = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> documentNames = new ConcurrentHashMap<>();

    /** Статус, который возвращается после загрузки архива (3 — подписано). */
    private int signedStatus = 3;

    public void setSignedStatus(int signedStatus) {
        this.signedStatus = signedStatus;
    }

    /** Собранный архив заявки (для проверок в тестах). */
    public byte[] packageOf(long orderId) {
        return packages.get(orderId);
    }

    public int statusOf(long orderId) {
        return statuses.getOrDefault(orderId, 0);
    }

    /** Изменить статус заявки (для имитации отказа/истечения срока в тестах). */
    public void setStatus(long orderId, int status) {
        statuses.put(orderId, status);
    }

    @Override
    public long createOrder(GosklyuchOrderMeta meta) {
        long id = ids.incrementAndGet();
        statuses.put(id, 0);
        return id;
    }

    @Override
    public void pushPackage(long orderId, byte[] zip, GosklyuchOrderMeta meta) {
        if (zip == null || zip.length == 0) {
            throw new IllegalArgumentException("Архив заявки пуст");
        }
        packages.put(orderId, zip.clone());
        documentNames.put(orderId, documentNamesIn(zip));
        statuses.put(orderId, signedStatus);
    }

    @Override
    public GosklyuchOrderInfo getOrder(long orderId) {
        int status = statuses.getOrDefault(orderId, 0);
        List<GosklyuchFile> files = new ArrayList<>();
        if (status == signedStatus) {
            for (String name : documentNames.getOrDefault(orderId, List.of())) {
                files.add(new GosklyuchFile(name + ".sig", 2, "application/octet-stream"));
            }
        }
        return new GosklyuchOrderInfo(orderId, status, "статус " + status, files);
    }

    @Override
    public byte[] downloadFile(long orderId, GosklyuchFile file) {
        return (SIGNATURE_PREFIX + orderId + ":" + file.fileName())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Имена документов в отправленном архиве (без req.xml и .sig). */
    private static List<String> documentNamesIn(byte[] zip) {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.equals("req.xml") && !name.endsWith(".sig")) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Некорректный архив заявки: " + e.getMessage(), e);
        }
        return names;
    }
}
