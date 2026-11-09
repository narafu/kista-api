package com.kista.tradingcore.broker.domain.model;

import java.util.List;

public record DailyTransactionResult(
        List<DailyTransaction> items   // 일별거래 목록
) {}
