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
    public List<AssetSnapshot> list(UUID userId, UUID requestedGroupId, LocalDate from, LocalDate to, UUID createdBy) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        return assetSnapshotPort.findByGroupId(groupId, from, to, createdBy);
    }

    @Override
    public AssetSnapshot create(UUID userId, UUID requestedGroupId, AssetSnapshotCommand command) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        verifyAssetCategory(groupId, command.categoryId());
        AssetSnapshot snapshot = new AssetSnapshot(null, groupId, command.categoryId(), command.accountId(), userId,
                command.entryDate(), command.assetClass(), command.market(), command.strategy(), command.amount(), null);
        AssetSnapshot saved = assetSnapshotPort.save(snapshot);
        log.info("자산 스냅샷 등록: groupId={}, snapshotId={}", groupId, saved.id());
        return saved;
    }

    @Override
    public AssetSnapshot update(UUID snapshotId, UUID userId, AssetSnapshotCommand command) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        verifyAssetCategory(existing.groupId(), command.categoryId());
        AssetSnapshot updated = new AssetSnapshot(existing.id(), existing.groupId(), command.categoryId(),
                command.accountId(), existing.createdBy(), command.entryDate(), command.assetClass(),
                command.market(), command.strategy(), command.amount(), existing.createdAt());
        return assetSnapshotPort.save(updated);
    }

    // categoryId가 이 그룹에서 실제로 접근 가능하고(시스템이거나 같은 그룹) type=ASSET인지 확인한다 — 없으면
    // 다른 그룹의 비공개 카테고리나 소비/수입 카테고리를 자산 스냅샷에 붙여도 그대로 저장된다.
    private void verifyAssetCategory(UUID groupId, UUID categoryId) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(categoryId);
        category.verifyOwnedBy(groupId);
        if (category.type() != FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산(ASSET) 카테고리만 자산 스냅샷에 사용할 수 있습니다");
        }
    }

    @Override
    public void delete(UUID snapshotId, UUID userId) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        assetSnapshotPort.softDelete(snapshotId);
        log.info("자산 스냅샷 삭제: snapshotId={}, userId={}", snapshotId, userId);
    }
}
