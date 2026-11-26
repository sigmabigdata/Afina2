package ru.afina.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.afina.entity.MonitorEvent;

import java.util.List;

public interface MonitorEventRepository extends JpaRepository<MonitorEvent, Long> {
    List<MonitorEvent> findAllByOrderByCheckedAtDesc(Pageable page);
    long countByStatus(String status);
}
