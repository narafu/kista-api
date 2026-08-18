package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.finance.MonthlyClosing;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// deleted_at 없음 — 그룹 단위 상태 레코드. 그룹 소프트 삭제(멤버 0) 시 deleteByGroupId로 하드 정리된다.
@Entity
@Table(name = "finance_monthly_closings", schema = "finance")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceMonthlyClosingEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", nullable = false, columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id

    @Column(name = "closed_by", columnDefinition = "UUID")
    private UUID closedBy;                // FK → users.id, 마감 해제 상태면 NULL

    @Column(nullable = false, length = 7)
    private String month;                 // 'YYYY-MM'

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "closed_at")
    private Instant closedAt;             // completed=true로 전환된 시각, null 허용

    static FinanceMonthlyClosingEntity fromModel(MonthlyClosing m) {
        FinanceMonthlyClosingEntity e = new FinanceMonthlyClosingEntity();
        e.id = m.id(); // null이면 @GeneratedValue가 UUID 생성
        e.groupId = m.groupId();
        e.closedBy = m.closedBy();
        e.month = m.month();
        e.completed = m.completed();
        e.closedAt = m.closedAt();
        return e;
    }

    static MonthlyClosing toDomain(FinanceMonthlyClosingEntity e) {
        return new MonthlyClosing(e.id, e.groupId, e.closedBy, e.month, e.completed, e.closedAt, e.getCreatedAt());
    }
}
