package com.kista.stats.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.MarginItem;
import com.kista.broker.domain.model.PresentBalanceResult;
import com.kista.broker.application.port.output.MarginPort;
import com.kista.broker.application.port.output.PortfolioPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

// account.broker() 기반 통계 라우터 — BrokerAdapterRegistry 경유
@Slf4j
@Component
@RequiredArgsConstructor
class BrokerStatisticsRouter {

    private final BrokerAdapterRegistry registry;

    // 체결기준현재잔고 — KIS: CTRP6504R+TTTC2101R 보정 포함 / Toss: 보유종목+예수금 직접 산출
    PresentBalanceResult getPresentBalance(Account account) {
        return registry.require(account.toBrokerRef(), PortfolioPort.class).getPresentBalance(account.toBrokerRef());
    }

    // 증거금 통화별 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
    List<MarginItem> getMargin(Account account) {
        return registry.require(account.toBrokerRef(), MarginPort.class).getMargin(account.toBrokerRef());
    }
}
