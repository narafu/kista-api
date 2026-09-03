package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 현재가·스냅샷 조회 — KIS: 계좌 토큰 사용 / Toss: 공통 API(account 파라미터 무시)
// 계약: 구현체는 null을 반환하지 않는다 — 조회 실패는 예외로 던진다 (KisPriceApi 패턴). 그래도 호출부(TradingPriceFetcher)는 null을 방어적으로 흡수한다 — 위반 시 크래시 대신 해당 ticker를 결과에서 배제
public interface BrokerPricePort {
    BigDecimal getPrice(StrategyTicker ticker, BrokerAccountRef account);
    Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers, BrokerAccountRef account);
    PriceSnapshot getPriceSnapshot(StrategyTicker ticker, BrokerAccountRef account);
    Map<StrategyTicker, PriceSnapshot> getPriceSnapshots(List<StrategyTicker> tickers, BrokerAccountRef account);
    // 전일종가만 필요한 경우 전용 — Toss는 현재가 API 호출 없이 캔들 API만 호출 (KIS는 현재가와 응답이 묶여 있어 절감 없음)
    BigDecimal getPrevClose(StrategyTicker ticker, BrokerAccountRef account);
    Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account);
    // 정규장 확정 종가 — 마감 리포트 전용(getPrevClose와 별도). KIS는 dailyprice, Toss/MOCK은 일봉 캔들(TossCandleApi) 기반 확정 종가 (봉 없으면 라이브가 폴백)
    BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account);
    Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account);
}
