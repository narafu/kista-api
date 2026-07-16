package com.kista.adapter.out.persistence.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;

interface RuntimeSettingsJpaRepository extends JpaRepository<RuntimeSettingsEntity, String> {

    @Modifying
    @Query(value = "INSERT INTO admin_runtime_settings (setting_key, setting_value) " +
            "VALUES (:settingKey, CAST(:settingValue AS jsonb)) ON CONFLICT (setting_key) DO NOTHING",
            nativeQuery = true)
    void insertIfMissing(String settingKey, String settingValue); // 삭제된 singleton 행의 원자적 기본값 복구

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select settings from RuntimeSettingsEntity settings where settings.settingKey = :settingKey")
    Optional<RuntimeSettingsEntity> findBySettingKeyForUpdate(String settingKey); // 승인 결정용 단일 행 잠금
}
