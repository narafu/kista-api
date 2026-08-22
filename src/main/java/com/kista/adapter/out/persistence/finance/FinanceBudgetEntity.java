package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.finance.FinanceBudget;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

// deleted_at 없음 — 파생 설정이라 하드 삭제 대상. finance_budgets_no_overlap EXCLUDE 제약이
// 소프트 삭제 행까지 감안하면 오히려 재등록을 막는 쪽으로 작동해 하드 삭제가 맞다 (§4.1).
@Entity
@Table(name = "finance_budgets", schema = "finance")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceBudgetEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id, NULL이면 개인 예산

    @Column(name = "category_id", nullable = false, columnDefinition = "UUID")
    private UUID categoryId;              // FK → finance_categories.id

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id, 소유자

    @Column(name = "apply_start_date", nullable = false)
    private LocalDate applyStartDate;

    @Column(name = "apply_end_date")
    private LocalDate applyEndDate;       // NULL = 무기한

    @Column(nullable = false)
    private long amount;                  // 월 할당 예산, 원화 정수

    static FinanceBudgetEntity fromModel(FinanceBudget b) {
        FinanceBudgetEntity e = new FinanceBudgetEntity();
        e.id = b.id(); // null이면 @GeneratedValue가 UUID 생성
        e.groupId = b.groupId();
        e.categoryId = b.categoryId();
        e.userId = b.userId();
        e.applyStartDate = b.applyStartDate();
        e.applyEndDate = b.applyEndDate();
        e.amount = b.amount();
        return e;
    }

    static FinanceBudget toDomain(FinanceBudgetEntity e) {
        return new FinanceBudget(
                e.id, e.groupId, e.categoryId, e.userId, e.applyStartDate, e.applyEndDate, e.amount,
                e.getCreatedAt());
    }
}
