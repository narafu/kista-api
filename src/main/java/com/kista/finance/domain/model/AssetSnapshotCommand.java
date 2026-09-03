package com.kista.finance.domain.model;

import java.time.LocalDate;
import java.util.UUID;

// 등록·수정 공용
public record AssetSnapshotCommand(
        UUID categoryId,
        UUID accountId, // null 허용 — 계좌 없는 자산(전세임차보증금 등)
        LocalDate entryDate,
        AssetClass assetClass,
        Market market,
        String strategy, // null 허용
        String memo, // null 허용
        long amount
) {}
