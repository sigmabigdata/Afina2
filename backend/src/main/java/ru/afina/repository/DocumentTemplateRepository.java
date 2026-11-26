package ru.afina.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.afina.entity.DocumentTemplate;

import java.util.List;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, Long> {

    List<DocumentTemplate> findAllByOrderBySortOrderAscNameAsc();

    List<DocumentTemplate> findAllByActiveTrueOrderBySortOrderAscNameAsc();

    /** Активные шаблоны только нужного назначения (обычные документы / согласия). */
    List<DocumentTemplate> findAllByActiveTrueAndKindOrderBySortOrderAscNameAsc(
            ru.afina.entity.TemplateKind kind);

    /** Активный шаблон согласия (первый по сортировке). */
    java.util.Optional<DocumentTemplate> findFirstByActiveTrueAndKindOrderBySortOrderAscNameAsc(
            ru.afina.entity.TemplateKind kind);
}
