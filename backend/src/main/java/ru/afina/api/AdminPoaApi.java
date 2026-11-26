package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.api.dto.PoaDto;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.entity.User;
import ru.afina.repository.PowerOfAttorneyRepository;
import ru.afina.service.PowerOfAttorneyService;
import ru.afina.service.PowerOfAttorneyXmlParser;
import ru.afina.util.FileNames;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Админский API управления машиночитаемыми доверенностями (МЧД).
 *
 * <p>Администратор загружает XML доверенности, включает и отключает её,
 * удаляет и скачивает исходный файл. Сама доверенность используется при
 * визуализации подписи документа.</p>
 *
 * <p>Все методы требуют роль {@code ROLE_ADMIN}; доступ дополнительно
 * ограничен IP-whitelist на уровне {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/poa")
public class AdminPoaApi {

    private static final Logger log = LoggerFactory.getLogger(AdminPoaApi.class);

    private final PowerOfAttorneyService poaService;
    private final PowerOfAttorneyRepository poaRepository;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;

    public AdminPoaApi(PowerOfAttorneyService poaService,
                       PowerOfAttorneyRepository poaRepository,
                       CurrentUser currentUser, ApiMapper mapper) {
        this.poaService = poaService;
        this.poaRepository = poaRepository;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    /** Все загруженные доверенности, включая истёкшие и отключённые. */
    @GetMapping
    public List<PoaDto> list(Authentication auth) {
        currentUser.requireAdmin(auth);
        return poaService.listAll().stream()
                .map(mapper::poa)
                .toList();
    }

    /**
     * Загрузка XML доверенности (multipart, поле {@code file}).
     *
     * <p>Файл разбирается сервисом: из него извлекаются ФИО представителя,
     * реквизиты, срок действия. Повторная загрузка того же файла
     * отклоняется как дубликат.</p>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PoaDto upload(@RequestPart(value = "file", required = false) MultipartFile file,
                         Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл не выбран");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null
                || !fileName.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            throw new IllegalArgumentException("Ожидается файл формата XML (.xml)");
        }

        byte[] xml;
        try {
            xml = file.getBytes();
        } catch (IOException e) {
            log.error("Не удалось прочитать загруженный файл МЧД '{}'", fileName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось прочитать загруженный файл");
        }

        try {
            PowerOfAttorney poa = poaService.upload(xml, admin.getEmail());
            return mapper.poa(poa);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (PowerOfAttorneyXmlParser.ParseException e) {
            // Ошибка в содержимом файла — это некорректный ввод, а не сбой сервера
            throw new IllegalArgumentException("Некорректный XML доверенности: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Не удалось загрузить МЧД '{}'", fileName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось загрузить доверенность");
        }
    }

    /** Включение или отключение доверенности; возвращает новое состояние. */
    @PatchMapping("/{id}/active")
    public PoaDto setActive(@PathVariable Long id,
                            @RequestBody(required = false) ActiveRequest req,
                            Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        if (req == null || req.active() == null) {
            throw new IllegalArgumentException("Укажите признак активности active");
        }
        poaService.setActive(id, req.active(), admin.getEmail());
        PowerOfAttorney poa = poaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Доверенность не найдена"));
        return mapper.poa(poa);
    }

    /** Удаление доверенности вместе с исходным XML. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        poaService.delete(id, admin.getEmail());
    }

    /**
     * Скачивание исходного XML доверенности.
     *
     * <p>Имя файла формируется из номера доверенности; если номер
     * отсутствует — из идентификатора записи.</p>
     */
    @GetMapping("/{id}/xml")
    public ResponseEntity<byte[]> xml(@PathVariable Long id, Authentication auth) {
        currentUser.requireAdmin(auth);
        PowerOfAttorney poa = poaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Доверенность не найдена"));
        String xmlContent = poa.getXmlContent();
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Исходный XML доверенности не сохранён");
        }
        String baseName = poa.getDocNumber() == null || poa.getDocNumber().isBlank()
                ? "poa-" + id
                : poa.getDocNumber();
        String downloadName = FileNames.sanitizeZipEntry(baseName) + ".xml";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(downloadName))
                .body(xmlContent.getBytes(StandardCharsets.UTF_8));
    }

    // ─────────────────────────── тела запросов ───────────────────────────

    /** Тело запроса переключения активности доверенности. */
    public record ActiveRequest(Boolean active) {
    }
}
