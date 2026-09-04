package com.kista.finance.adapter.out.persistence;

import com.kista.platform.persistence.BaseAuditEntity;
import com.kista.finance.domain.model.FinanceAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// @SQLRestriction("deleted_at IS NULL")를 의도적으로 붙이지 않는다 (FinanceCategoryEntity §4.2와 동일 이유).
// 계좌는 읽기 모드가 둘: (A) 폼 선택지 = 활성 행만 → findByGroupId가 명시적으로 필터링.
// (B) 과거 자산 기록의 계좌명 렌더링(AssetSnapshotController.enrich) = 삭제된 행도 조회돼야 함 → findById는 무필터.
// 클래스 레벨 @SQLRestriction을 걸면 (B)가 죽어, 계좌 삭제 후에도 남아있는 자산 스냅샷이 목록 조회 전체를
// 404(NoSuchElementException)로 깨뜨린다 — 운영에서 실제로 발생한 장애(2026-08-19).
@Entity
@Table(name = "finance_accounts", schema = "finance")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceAccountEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id, NULL이면 개인 계좌

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id, 소유자

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private FinanceAccount.Type accountType;

    @Column(nullable = false, length = 50)
    private String name;                  // 계좌명 (예: 토스증권 일반계좌)

    @Column(name = "account_no", length = 512)
    private String accountNo;             // AES-256 암호화 저장, 선택

    @Column(name = "account_no_hash", length = 64)
    private String accountNoHash;         // HMAC-SHA256 해시 (전역 중복 체크용, accountNo 없으면 null)

    @Column(length = 255)
    private String memo;                  // 선택

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

    // 암/복호화는 persistence 경계(FinanceAccountPersistenceAdapter)에서 수행 — 이 메서드에는 평문/암호문 어느 쪽이든 그대로 들어온다.
    static FinanceAccountEntity fromModel(FinanceAccount a) {
        FinanceAccountEntity e = new FinanceAccountEntity();
        e.id = a.id(); // null이면 @GeneratedValue가 UUID 생성
        e.groupId = a.groupId();
        e.userId = a.userId();
        e.accountType = a.accountType();
        e.name = a.name();
        e.accountNo = a.accountNo();
        e.memo = a.memo();
        return e;
    }

    static FinanceAccount toDomain(FinanceAccountEntity e) {
        return new FinanceAccount(
                e.id, e.groupId, e.userId, e.accountType, e.name, e.accountNo, e.memo, e.getCreatedAt());
    }
}
