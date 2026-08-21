package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.finance.FinanceGroup;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_groups", schema = "finance")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceGroupEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, columnDefinition = "UUID")
    private UUID ownerUserId;             // FK → users.id, 그룹 생성자(개인 그룹은 본인)

    @Column(nullable = false, length = 50)
    private String name;                  // 표시명

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

    static FinanceGroupEntity fromModel(FinanceGroup g) {
        FinanceGroupEntity e = new FinanceGroupEntity();
        e.id = g.id(); // null이면 @GeneratedValue가 UUID 생성
        e.ownerUserId = g.ownerUserId();
        e.name = g.name();
        return e;
    }

    static FinanceGroup toDomain(FinanceGroupEntity e) {
        return new FinanceGroup(e.id, e.ownerUserId, e.name, e.getCreatedAt());
    }
}
