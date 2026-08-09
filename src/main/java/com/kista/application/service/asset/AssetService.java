package com.kista.application.service.asset;

import com.kista.domain.model.asset.Asset;
import com.kista.domain.model.asset.RegisterAssetCommand;
import com.kista.domain.model.asset.UpdateAssetCommand;
import com.kista.domain.port.in.AssetUseCase;
import com.kista.domain.port.out.AssetPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AssetService implements AssetUseCase {

    private final AssetPort assetPort;

    @Override
    public Asset register(UUID userId, RegisterAssetCommand cmd) {
        if (cmd.amount() < 0) {
            throw new IllegalArgumentException("금액은 0 이상이어야 합니다");
        }
        Asset asset = new Asset(null, userId, cmd.entryDate(), cmd.category(),
                cmd.subcategory(), cmd.institution(), cmd.assetClass(), cmd.strategy(),
                cmd.amount(), null);
        Asset saved = assetPort.save(asset);
        log.info("자산 기록 등록: userId={}, assetId={}", userId, saved.id());
        return saved;
    }

    @Override
    public Asset update(UUID assetId, UUID requesterId, UpdateAssetCommand cmd) {
        if (cmd.amount() < 0) {
            throw new IllegalArgumentException("금액은 0 이상이어야 합니다");
        }
        Asset existing = assetPort.requireOwnedAsset(assetId, requesterId);
        Asset updated = new Asset(existing.id(), existing.userId(), cmd.entryDate(), cmd.category(),
                cmd.subcategory(), cmd.institution(), cmd.assetClass(), cmd.strategy(),
                cmd.amount(), existing.createdAt());
        return assetPort.save(updated);
    }

    @Override
    public void delete(UUID assetId, UUID requesterId) {
        assetPort.requireOwnedAsset(assetId, requesterId);
        assetPort.delete(assetId);
        log.info("자산 기록 삭제: assetId={}, requesterId={}", assetId, requesterId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Asset> listByUser(UUID userId) {
        return assetPort.findByUserId(userId);
    }
}
