package com.kista.domain.port.in;

import com.kista.domain.model.asset.AssetMonthlyCheck;

import java.util.List;
import java.util.UUID;

public interface AssetMonthlyCheckUseCase {
    List<AssetMonthlyCheck> listByUser(UUID userId);
    AssetMonthlyCheck setCompleted(UUID userId, String month, boolean completed);
}
