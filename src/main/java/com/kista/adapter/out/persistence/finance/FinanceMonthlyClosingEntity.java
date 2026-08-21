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

    @Column(name = "group_id", columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id, NULL이면 개인 마감

    // 옛 closed_by를 소유자 축(user_id)으로 승격 — 마감 해제해도 더는 null로 되돌리지 않는다
    // (개인 스코프 유니크 인덱스가 user_id NOT NULL을 전제하므로, null로 되돌리면 재완료 시
    // UNIQUE 매칭이 깨져 중복 행이 생긴다). "누가 마지막으로 마감했는지"는 closed_at으로 충분히 갈음된다.
    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id, 소유자

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
        e.userId = m.userId();
        e.month = m.month();
        e.completed = m.completed();
        e.closedAt = m.closedAt();
        return e;
    }

    static MonthlyClosing toDomain(FinanceMonthlyClosingEntity e) {
        return new MonthlyClosing(e.id, e.groupId, e.userId, e.month, e.completed, e.closedAt, e.getCreatedAt());
    }
}
