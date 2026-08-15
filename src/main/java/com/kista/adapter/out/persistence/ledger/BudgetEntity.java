package com.kista.adapter.out.persistence.ledger;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "ledger_budgets")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class BudgetEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Column(name = "category_id", nullable = false, columnDefinition = "UUID")
    private UUID categoryId;              // FK → ledger_categories.id

    @Column(name = "apply_start_date", nullable = false)
    private LocalDate applyStartDate;     // 이 날짜부터 다음 행 시작일 전까지 유효

    @Column(nullable = false)
    private long amount;                  // 월 할당 예산, 원화 정수. 0이면 예산 종료를 의미
}
