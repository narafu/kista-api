package com.kista.adapter.out.persistence.settings;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "admin_runtime_settings")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor
@AllArgsConstructor
class RuntimeSettingsEntity extends BaseAuditEntity {

    @Id
    @Column(name = "setting_key", nullable = false, length = 100, updatable = false)
    private String settingKey; // 설정 행 식별 키

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "setting_value", nullable = false, columnDefinition = "jsonb")
    private String settingValue; // 원본 JSON 설정 값
}
