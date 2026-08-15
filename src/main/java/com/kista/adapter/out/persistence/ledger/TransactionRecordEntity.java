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
@Table(name = "ledger_transaction_records")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TransactionRecordEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Column(name = "category_id", nullable = false, columnDefinition = "UUID")
    private UUID categoryId;              // FK → ledger_categories.id

    @Column(nullable = false)
    private long amount;                  // 원화 정수 절대값. 부호는 Category.Type.sign이 SSOT

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;    // 거래 발생일

    @Column(length = 255)
    private String memo;                  // 자유 입력, null 허용
}
