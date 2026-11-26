package ru.afina.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentVisualization;

import java.util.Optional;

public interface DocumentVisualizationRepository extends JpaRepository<DocumentVisualization, Long> {
    Optional<DocumentVisualization> findByDocument(Document document);}
