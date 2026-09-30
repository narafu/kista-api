package com.kista.broker.application.port.output;

// 모든 증권사 어댑터 공통 capability — 어댑터 식별(supports) + 공통 7개 Port
public interface BrokerCapabilitiesPort extends BrokerAdapterPort,
        PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort,
        ExecutionPort,
        BrokerPricePort, LiveBalancePort {
}
