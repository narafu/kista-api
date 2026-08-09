package com.kista.domain.port.in;

import com.kista.domain.model.asset.Asset;
import com.kista.domain.model.asset.RegisterAssetCommand;
import com.kista.domain.model.asset.UpdateAssetCommand;

import java.util.List;
import java.util.UUID;

public interface AssetUseCase {
    List<Asset> listByUser(UUID userId);
    Asset register(UUID userId, RegisterAssetCommand command);
    Asset update(UUID assetId, UUID requesterId, UpdateAssetCommand command);
    void delete(UUID assetId, UUID requesterId);
}
