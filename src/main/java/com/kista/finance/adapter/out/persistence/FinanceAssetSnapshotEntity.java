package com.kista.finance.adapter.out.persistence;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.finance.domain.model.AssetClass;
import com.kista.finance.domain.model.AssetSnapshot;
import com.kista.finance.domain.model.Market;
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
@Table(name = "finance_asset_snapshots", schema = "finance")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceAssetSnapshotEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id, NULL이면 개인 소유

    @Column(name = "category_id", nullable = false, columnDefinition = "UUID")
    private UUID categoryId;              // FK → finance_categories.id (type='ASSET'인 L1 또는 L2)

    @Column(name = "account_id", columnDefinition = "UUID")
    private UUID accountId;               // FK → finance_accounts.id, 계좌 없는 자산은 NULL

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id, 소유자

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 20)
    private AssetClass assetClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Market market;

    @Column(length = 50)
    private String strategy;              // 자유 입력, 선택

    @Column(length = 255)
    private String memo;                  // 자유 입력, 선택 — strategy와 별개의 자유 메모

    @Column(nullable = false)
    private long amount;                  // 원화 정수, 0 이상

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

    static FinanceAssetSnapshotEntity fromModel(AssetSnapshot s) {
        FinanceAssetSnapshotEntity e = new FinanceAssetSnapshotEntity();
        e.id = s.id(); // null이면 @GeneratedValue가 UUID 생성
        e.groupId = s.groupId();
        e.categoryId = s.categoryId();
        e.accountId = s.accountId();
        e.userId = s.userId();
        e.entryDate = s.entryDate();
        e.assetClass = s.assetClass();
        e.market = s.market();
        e.strategy = s.strategy();
        e.memo = s.memo();
        e.amount = s.amount();
        return e;
    }

    static AssetSnapshot toDomain(FinanceAssetSnapshotEntity e) {
        return new AssetSnapshot(
                e.id, e.groupId, e.categoryId, e.accountId, e.userId, e.entryDate,
                e.assetClass, e.market, e.strategy, e.memo, e.amount, e.getCreatedAt());
    }
}
