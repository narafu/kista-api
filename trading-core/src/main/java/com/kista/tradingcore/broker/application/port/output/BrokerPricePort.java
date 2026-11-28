package com.kista.tradingcore.broker.application.port.output;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

// 주문 경로 현재가 — 그 계좌의 증권사에서 직접 읽는다(Mock은 공용 시세)
// 계약: 구현체는 null을 반환하지 않는다 — 조회 실패는 예외로 던진다. 그래도 호출부(TradingPriceFetcher)는 null을 방어적으로 흡수한다 — 위반 시 크래시 대신 해당 ticker를 결과에서 배제
// 부분 실패: Toss는 응답에 없는 종목의 키가 빠지고(호출부가 키 부재 처리), Meritz는 한 종목이라도 실패하면 예외(계좌 단위 실패)
public interface BrokerPricePort {
    Map<StrategyTicker, BigDecimal> currentPrices(BrokerAccountRef account, Collection<StrategyTicker> tickers);
}
