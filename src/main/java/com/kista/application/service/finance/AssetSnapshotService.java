package com.kista.application.service.finance;

import com.kista.domain.model.finance.AssetSnapshot;
import com.kista.domain.model.finance.AssetSnapshotCommand;
import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.port.in.AssetSnapshotUseCase;
import com.kista.domain.port.out.AssetSnapshotPort;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
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

    @Override
    @Transactional(readOnly = true)
    public List<AssetSnapshot> list(UUID userId, UUID requestedGroupId, LocalDate from, LocalDate to, UUID filterUserId) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return assetSnapshotPort.findMyScope(userId, currentGroupId, from, to, filterUserId);
    }

    // 신규 등록은 항상 개인 소유로 저장한다 — requestedGroupId는 무시.
    @Override
    public AssetSnapshot create(UUID userId, UUID requestedGroupId, AssetSnapshotCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        verifyAssetCategory(userId, currentGroupId, command.categoryId());
        AssetSnapshot snapshot = new AssetSnapshot(null, null, command.categoryId(), command.accountId(), userId,
                command.entryDate(), command.assetClass(), command.market(), command.strategy(), command.amount(), null);
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
        AssetSnapshot updated = new AssetSnapshot(existing.id(), existing.groupId(), command.categoryId(),
                command.accountId(), existing.userId(), command.entryDate(), command.assetClass(),
                command.market(), command.strategy(), command.amount(), existing.createdAt());
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
        assetSnapshotPort.softDelete(snapshotId);
        log.info("자산 스냅샷 삭제: snapshotId={}, userId={}", snapshotId, userId);
    }

    // 개인 소유 자산 기록을 소유자가 자신의 현재 그룹으로 전환한다. 본인 것만 전환 가능(그룹 멤버 전체 아님).
    @Override
    public AssetSnapshot shareToGroup(UUID snapshotId, UUID userId) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        return GroupShareSupport.shareToGroup(existing, userId, financeGroupPort.findCurrentGroupId(userId),
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
        return GroupShareSupport.unshare(existing, userId, currentGroupId)
                .map(personal -> {
                    AssetSnapshot saved = assetSnapshotPort.save(personal);
                    log.info("자산 기록 그룹 공유 해제: snapshotId={}, userId={}", snapshotId, userId);
                    return saved;
                })
                .orElse(existing);
    }
}
