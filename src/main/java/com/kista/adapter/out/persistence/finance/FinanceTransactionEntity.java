package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.finance.FinanceTransaction;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "finance_transactions", schema = "finance")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceTransactionEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id, NULL이면 개인 소유

    @Column(name = "category_id", nullable = false, columnDefinition = "UUID")
    private UUID categoryId;              // FK → finance_categories.id

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id, 소유자 — 개인/그룹 탭 필터 기준

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(nullable = false)
    private long amount;                  // 원화 정수 절대값. 부호는 FinanceCategory.Type.sign이 SSOT

    @Column(length = 255)
    private String memo;                  // 선택

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

    static FinanceTransactionEntity fromModel(FinanceTransaction t) {
        FinanceTransactionEntity e = new FinanceTransactionEntity();
        e.id = t.id(); // null이면 @GeneratedValue가 UUID 생성
        e.groupId = t.groupId();
        e.categoryId = t.categoryId();
        e.userId = t.userId();
        e.transactionDate = t.transactionDate();
        e.amount = t.amount();
        e.memo = t.memo();
        return e;
    }

    static FinanceTransaction toDomain(FinanceTransactionEntity e) {
        return new FinanceTransaction(
                e.id, e.groupId, e.categoryId, e.userId, e.transactionDate, e.amount, e.memo, e.getCreatedAt());
    }
}
