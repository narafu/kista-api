package com.kista.domain.model.asset;

import java.time.LocalDate;

public record RegisterAssetCommand(
        LocalDate entryDate,
        AssetCategory category,
        String subcategory,
        String institution,
        String assetClass,
        String strategy,
        long amount
) {}
