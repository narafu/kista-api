package com.kista.tradingcore.broker.application.port.output;

import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 계좌 자격증명 없이 관리자 키로 조회하는 시세 — Toss 관리자 → KIS 관리자 순으로 누락 종목만 다음 단계로 넘긴다.
// 계약: 예외를 던지지 않고 부분 결과를 반환한다 — 결과에 없는 종목 = 관리자 단계 전부 실패(호출부가 계좌 기준 조회로 이어간다).
// 각 단계는 대체값으로 채우지 않는다(전일종가 실패를 현재가로, 확정 종가 실패를 라이브가로 메우지 않음) — 대체는 계좌 단계만 한다
public interface AdminMarketPricePort {
    // 현재가(확장시간 체결가 포함)
    Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers);
    // 전일 확정 종가
    Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers);
    // tradeDate(KST 거래일) 정규장 확정 종가
    Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate);
}
