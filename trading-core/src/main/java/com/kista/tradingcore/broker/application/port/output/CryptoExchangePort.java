package com.kista.tradingcore.broker.application.port.output;

import com.kista.shared.kernel.Broker;
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

// 코인 거래소 포트 — 정수 holdings·LOC/MOC 전제의 BrokerCapabilitiesPort와 분리. CryptoExchanges.of(broker)로 라우팅(2차 빗썸은 구현체만 추가)
public interface CryptoExchangePort {
    // 이 구현체가 담당하는 거래소
    Broker supports();

    // 원화 가용·잠금 + 지원 코인 보유·평단
    CryptoBalance getBalance(BrokerAccountRef account);

    // 현재가 (KRW) — 공개 시세, 계좌 불필요
    Map<StrategyTicker, BigDecimal> getCurrentPrices(Collection<StrategyTicker> tickers);

    // 완성 일봉 최근 count개(1~400, 이력 부족 시 적게), 오래된 순 — 진행 중인 오늘 봉(KST 09:00 시작)은 제외
    List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count);

    // 금액 기준 시장가 매수 — identifier는 계정 내 영구 고유(재사용 불가)
    CryptoOrderResult buyMarketByAmount(BrokerAccountRef account, StrategyTicker ticker, BigDecimal krwAmount, String identifier);

    // 수량 기준 시장가 매도
    CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String identifier);

    // 주문 조회 — 없으면 empty(재실행 시 identifier로 접수 여부 복구)
    Optional<CryptoOrderResult> findOrderByUuid(BrokerAccountRef account, String uuid);

    Optional<CryptoOrderResult> findOrderByIdentifier(BrokerAccountRef account, String identifier);
}
