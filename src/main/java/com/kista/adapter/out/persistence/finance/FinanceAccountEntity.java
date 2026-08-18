package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.finance.FinanceAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_accounts", schema = "finance")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceAccountEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", nullable = false, columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id

    @Column(name = "created_by", nullable = false, columnDefinition = "UUID")
    private UUID createdBy;               // FK → users.id

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private FinanceAccount.Type accountType;

    @Column(nullable = false, length = 50)
    private String name;                  // 계좌명 (예: 토스증권 일반계좌)

    @Column(name = "account_no", length = 512)
    private String accountNo;             // AES-256 암호화 저장, 선택

    @Column(length = 255)
    private String memo;                  // 선택

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

    // 암/복호화는 persistence 경계(FinanceAccountPersistenceAdapter)에서 수행 — 이 메서드에는 평문/암호문 어느 쪽이든 그대로 들어온다.
    static FinanceAccountEntity fromModel(FinanceAccount a) {
        FinanceAccountEntity e = new FinanceAccountEntity();
        e.id = a.id(); // null이면 @GeneratedValue가 UUID 생성
        e.groupId = a.groupId();
        e.createdBy = a.createdBy();
        e.accountType = a.accountType();
        e.name = a.name();
        e.accountNo = a.accountNo();
        e.memo = a.memo();
        return e;
    }

    static FinanceAccount toDomain(FinanceAccountEntity e) {
        return new FinanceAccount(
                e.id, e.groupId, e.createdBy, e.accountType, e.name, e.accountNo, e.memo, e.getCreatedAt());
    }
}
