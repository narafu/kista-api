package com.kista.tradingcore.broker.application.port.output;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.CryptoTradingRules;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 코인 거래소 포트 — 정수 holdings·LOC/MOC 전제의 BrokerCapabilitiesPort와 분리. CryptoExchanges.of(broker)로 라우팅(거래소별 구현체만 추가)
public interface CryptoExchangePort {
    // 이 구현체가 담당하는 거래소
    Broker supports();

    // 견적 통화 가용·잠금 + 지원 코인 보유·평단
    CryptoBalance getBalance(BrokerAccountRef account);

    // 종목별 실행 규칙(수수료·차감 쪽·최소 명목가·수량 step) — 계좌 불필요, 조회 실패는 예외
    CryptoTradingRules getTradingRules(StrategyTicker ticker);

    // 현재가 (견적 통화) — 공개 시세, 계좌 불필요
    Map<StrategyTicker, BigDecimal> getCurrentPrices(Collection<StrategyTicker> tickers);

    // 완성 일봉 최근 count개(1~400, 이력 부족 시 적게), 오래된 순 — 진행 중인 오늘 봉은 제외
    // 마지막 봉 일자 계약 = CryptoCandle.latestCompletedKstDate(now)
    List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count);

    // 금액 기준 시장가 매수 — clientOrderId = 주문 UUID. 같은 값으로 재전송 금지 — 고유 범위가 거래소마다 다르다(업비트 영구, 바이낸스 열린 주문 중에서만)
    CryptoOrderResult buyMarketByAmount(BrokerAccountRef account, StrategyTicker ticker, BigDecimal quoteAmount, String clientOrderId);

    // 수량 기준 시장가 매도
    CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String clientOrderId);

    // 주문 조회 — empty는 거래소가 '없음'을 확정 응답했을 때만. 네트워크·5xx는 예외(동기화기가 다음 틱 재시도, empty로 바꾸면 FAILED → 재판정 → 이중 매수)
    Optional<CryptoOrderResult> findOrderByExchangeOrderId(BrokerAccountRef account, StrategyTicker ticker, String exchangeOrderId);

    Optional<CryptoOrderResult> findOrderByClientOrderId(BrokerAccountRef account, StrategyTicker ticker, String clientOrderId);
}
