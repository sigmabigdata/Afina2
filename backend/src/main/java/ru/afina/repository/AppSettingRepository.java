package ru.afina.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.afina.entity.AppSetting;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
