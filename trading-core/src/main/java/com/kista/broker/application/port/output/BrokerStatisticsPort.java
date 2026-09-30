package com.kista.broker.application.port.output;

// 통계·시세 조회 capability 묶음(선택 기능) — 캔들/종목정보/환율/장 캘린더/증권사 계좌 목록. 현재 TossBrokerAdapter만 구현한다.
// 모든 시그니처가 벤더 중립 값 타입(BrokerCandle 등)이라 소비자(tradingstats)는 증권사 구체 타입을 몰라도 된다.
// 공통 7개 묶음(BrokerCapabilitiesPort)과 달리 모든 증권사가 구현하지는 않으므로 BrokerStatisticsPorts.find(broker)로 지원 여부를 조회한다
public interface BrokerStatisticsPort extends BrokerAdapterPort,
        CandlePort, StockInfoPort, ExchangeRatePort,
        BrokerMarketCalendarPort, BrokerAccountPort {
}
