package com.kista.domain.model.asset;

public record AssetMonthlyCheck(
        String month,       // 'YYYY-MM'
        boolean completed
) {}
