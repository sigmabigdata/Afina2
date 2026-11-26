package ru.afina.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.api.dto.FolderDto;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.User;
import ru.afina.repository.DocumentRepository;
import ru.afina.service.AuditService;
import ru.afina.service.FolderService;

import java.util.List;
import java.util.Map;

/** Разделы (папки) документов клиента. */
@RestController
@RequestMapping("/api/v1/folders")
public class FolderApi {

    private final FolderService folderService;
    private final DocumentRepository documentRepository;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;
    private final AuditService audit;

    public FolderApi(FolderService folderService, DocumentRepository documentRepository,
                     CurrentUser currentUser, ApiMapper mapper, AuditService audit) {
        this.folderService = folderService;
        this.documentRepository = documentRepository;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.audit = audit;
    }

    @GetMapping
    public List<FolderDto> list(Authentication auth) {
        User user = currentUser.entity(auth);
        return folderService.listForOwner(user).stream()
                .map(f -> mapper.folder(f,
                        documentRepository.findAllByFolderOrderByUploadedAtDesc(f).size(),
                        documentRepository.countByFolderAndSignedTrue(f)))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FolderDto create(@RequestBody Map<String, String> body, Authentication auth) {
        User user = currentUser.entity(auth);
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите название раздела");
        }
        DocumentFolder folder = folderService.create(user, name);
        audit.folderCreate(user.getEmail(), folder.getId(), folder.getName());
        return mapper.folder(folder, 0, 0);
    }

    @PatchMapping("/{id}")
    public FolderDto rename(@PathVariable Long id, @RequestBody Map<String, String> body,
                            Authentication auth) {
        User user = currentUser.entity(auth);
        FolderService f = folderService;
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите название раздела");
        }
        f.rename(id, user, name);
        DocumentFolder folder = f.getOwned(id, user);
        audit.folderRename(user.getEmail(), id, folder.getName());
        return mapper.folder(folder,
                documentRepository.findAllByFolderOrderByUploadedAtDesc(folder).size(),
                documentRepository.countByFolderAndSignedTrue(folder));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        User user = currentUser.entity(auth);
        DocumentFolder folder = folderService.getOwned(id, user);
        long signed = folderService.countSignedInFolder(id, user);
        if (signed > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "В разделе есть подписанные документы — удаление запрещено");
        }
        folderService.delete(id, user);
        audit.folderDelete(user.getEmail(), id, folder.getName(), signed);
    }
}
