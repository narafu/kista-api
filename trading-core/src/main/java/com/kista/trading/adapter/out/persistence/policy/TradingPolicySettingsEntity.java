package com.kista.trading.adapter.out.persistence.policy;

import com.kista.platform.persistence.BaseAuditEntity;
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

// 매매 런타임 정책 단일 행(jsonb) — root admin_runtime_settings에서 brokers/strategies 섹션이 trading 소유로 이동한 것.
// @Id가 할당식(@GeneratedValue 없음)이라 save()가 곧 upsert(merge)다.
@Entity
@Table(name = "trading_runtime_settings", schema = "trading")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor
@AllArgsConstructor
class TradingPolicySettingsEntity extends BaseAuditEntity {

    @Id
    @Column(name = "setting_key", nullable = false, length = 100, updatable = false)
    private String settingKey; // 설정 행 식별 키

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "setting_value", nullable = false, columnDefinition = "jsonb")
    private String settingValue; // TradingPolicySettings 직렬화 JSON
}
