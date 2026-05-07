package com.kista.domain.model;

import java.util.List;

public record DailyTransactionResult(
        List<DailyTransaction> items,   // 일별거래 목록 (output1)
        DailyTransactionSummary summary // 합계 요약 (output2)
) {}
