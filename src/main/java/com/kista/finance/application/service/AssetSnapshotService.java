package com.kista.finance.application.service;

import com.kista.finance.domain.model.AssetSnapshot;
import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.application.usecase.AssetSnapshotUseCase;
import com.kista.finance.application.port.output.AssetSnapshotPort;
import com.kista.finance.application.port.output.FinanceCategoryPort;
import com.kista.finance.application.port.output.FinanceGroupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 구 AssetService 대체 — category/subcategory enum+자유텍스트가 categoryId FK로 승격됐다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AssetSnapshotService implements AssetSnapshotUseCase {

    private final AssetSnapshotPort assetSnapshotPort;
    private final FinanceGroupPort financeGroupPort;
    private final FinanceCategoryPort financeCategoryPort;
    private final MonthlyClosingGuard monthlyClosingGuard;

    @Override
    @Transactional(readOnly = true)
    public List<AssetSnapshot> list(UUID userId, UUID requestedGroupId, LocalDate from, LocalDate to, UUID filterUserId) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return assetSnapshotPort.findMyScope(userId, currentGroupId, from, to, filterUserId);
    }

    // shareToGroup=true면 소유자의 현재 그룹 소유로, false면 개인 소유로 원자적 생성한다.
    @Override
    public AssetSnapshot create(UUID userId, boolean shareToGroup, AssetSnapshotCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        verifyAssetCategory(userId, currentGroupId, command.categoryId());
        // 기록 점검 완료월에는 신규 등록 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, command.entryDate());
        // 그룹 공유를 요청했는데 소속 그룹이 없으면 거부 (GroupShareSupport와 동일 메시지)
        if (shareToGroup && currentGroupId == null) {
            throw new IllegalStateException("소속된 그룹이 없습니다");
        }
        UUID ownerGroupId = shareToGroup ? currentGroupId : null;
        AssetSnapshot snapshot = new AssetSnapshot(null, ownerGroupId, command.categoryId(), command.accountId(), userId,
                command.entryDate(), command.assetClass(), command.market(), command.strategy(), command.memo(), command.amount(), null);
        AssetSnapshot saved = assetSnapshotPort.save(snapshot);
        log.info("자산 스냅샷 등록: userId={}, snapshotId={}", userId, saved.id());
        return saved;
    }

    @Override
    public AssetSnapshot update(UUID snapshotId, UUID userId, AssetSnapshotCommand command) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        verifyAssetCategory(userId, currentGroupId, command.categoryId());
        // 마감월에서 빼내기(existing) + 마감월로 넣기(command) 둘 다 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.entryDate());
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, command.entryDate());
        AssetSnapshot updated = new AssetSnapshot(existing.id(), existing.groupId(), command.categoryId(),
                command.accountId(), existing.userId(), command.entryDate(), command.assetClass(),
                command.market(), command.strategy(), command.memo(), command.amount(), existing.createdAt());
        return assetSnapshotPort.save(updated);
    }

    // categoryId가 실제로 접근 가능하고(시스템이거나 본인/내 그룹 소유) type=ASSET인지 확인한다.
    private void verifyAssetCategory(UUID userId, UUID currentGroupId, UUID categoryId) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(categoryId);
        category.verifyAccessibleBy(userId, currentGroupId);
        if (category.type() != FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산(ASSET) 카테고리만 자산 스냅샷에 사용할 수 있습니다");
        }
    }

    @Override
    public void delete(UUID snapshotId, UUID userId) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        // 마감월 기록은 삭제도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.entryDate());
        assetSnapshotPort.softDelete(snapshotId);
        log.info("자산 스냅샷 삭제: snapshotId={}, userId={}", snapshotId, userId);
    }

    // 개인 소유 자산 기록을 소유자가 자신의 현재 그룹으로 전환한다. 본인 것만 전환 가능(그룹 멤버 전체 아님).
    @Override
    public AssetSnapshot shareToGroup(UUID snapshotId, UUID userId) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 마감월 기록은 그룹 공유 전환도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.entryDate());
        return GroupShareSupport.shareToGroup(existing, userId, Optional.ofNullable(currentGroupId),
                        "본인 소유 자산 기록만 그룹에 공유할 수 있습니다")
                .map(shared -> {
                    AssetSnapshot saved = assetSnapshotPort.save(shared);
                    log.info("자산 기록 그룹 공유 전환: snapshotId={}, groupId={}", snapshotId, saved.groupId());
                    return saved;
                })
                .orElse(existing);
    }

    // 그룹 공유 자산 기록을 개인 소유로 되돌린다. 소유자는 그대로 유지, groupId만 null로.
    @Override
    public AssetSnapshot unshare(UUID snapshotId, UUID userId) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 마감월 기록은 귀속 해제도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.entryDate());
        return GroupShareSupport.unshare(existing, userId, currentGroupId)
                .map(personal -> {
                    AssetSnapshot saved = assetSnapshotPort.save(personal);
                    log.info("자산 기록 그룹 공유 해제: snapshotId={}, userId={}", snapshotId, userId);
                    return saved;
                })
                .orElse(existing);
    }
}
