package com.kista.tradingstats.application.usecase;

import com.kista.broker.domain.model.BrokerAccountInfo;
import com.kista.broker.domain.model.BrokerCandle;
import com.kista.broker.domain.model.BrokerStockInfo;
import com.kista.broker.domain.model.ExchangeRateQuote;
import com.kista.broker.domain.model.MarketCalendarDay;
import com.kista.sharedkernel.StrategyTicker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 증권사 통계 기능(선택 capability) — 지원 증권사 계좌에서만 접근 가능 (미지원 증권사 호출 시 IllegalArgumentException → 400). 현재 지원: TOSS
public interface BrokerStatisticsUseCase {
    // 캔들차트
    List<BrokerCandle> getCandles(UUID accountId, UUID requesterId, StrategyTicker ticker, String interval, LocalDate from, LocalDate to);
    // 종목 기본 정보
    BrokerStockInfo getStockInfo(UUID accountId, UUID requesterId, StrategyTicker ticker);
    // 환율 (USD/KRW)
    ExchangeRateQuote getExchangeRate(UUID accountId, UUID requesterId);
    // 현재 환율 (USD/KRW) — 계좌 무관 공개 API라 소유권 검증 없이 조회(내부 API용)
    ExchangeRateQuote currentExchangeRate();
    // 해외 장 운영 정보
    List<MarketCalendarDay> getMarketCalendar(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    // 증권사 계좌 목록
    List<BrokerAccountInfo> getAccountList(UUID accountId, UUID requesterId);
}
