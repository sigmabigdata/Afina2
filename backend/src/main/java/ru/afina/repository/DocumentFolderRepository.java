package ru.afina.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.User;

import java.util.List;
import java.util.Optional;

public interface DocumentFolderRepository extends JpaRepository<DocumentFolder, Long> {

    /** Сортировка по дате создания: новые сверху. */
    List<DocumentFolder> findAllByOwnerOrderByCreatedAtDesc(User owner);

    Optional<DocumentFolder> findByIdAndOwner(Long id, User owner);

    boolean existsByOwner(User owner);

    /** Проверка дубликата имени в рамках клиента (по hash). */
    boolean existsByOwnerAndNameHash(User owner, String nameHash);

    /** Поиск «папки по умолчанию» (для legacy-документов). */
    @Query("select f from DocumentFolder f where f.owner = :owner " +
           "and f.nameHash = :nameHash")
    Optional<DocumentFolder> findByOwnerAndNameHash(@Param("owner") User owner,
                                                     @Param("nameHash") String nameHash);
}
