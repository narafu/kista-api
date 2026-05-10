package com.kista.adapter.out.persistence;

import com.kista.domain.model.Strategy;
import com.kista.domain.model.StrategyStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "strategies")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "UUID")
    private UUID accountId; // FK → accounts.id (ON DELETE CASCADE)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false, length = 20)
    private Strategy type; // 매매 전략 종류 (INFINITE, PRIVACY)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, length = 10)
    private StrategyStatus status; // 실행 상태 (ACTIVE, PAUSED)
}
