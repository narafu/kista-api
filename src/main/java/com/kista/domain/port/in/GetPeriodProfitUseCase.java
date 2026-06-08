package com.kista.domain.port.in;

import com.kista.domain.model.kis.PeriodProfitResult;

import java.time.LocalDate;
import java.util.UUID;

// KIS TTTS3039R — 기간 종목별 실현손익 조회
public interface GetPeriodProfitUseCase {
    PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
}
