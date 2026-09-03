package com.kista.application.service.account;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerAccountRef;
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
        return registry.require(toBrokerRef(account), PortfolioPort.class).getPresentBalance(toBrokerRef(account));
    }

    // 증거금 통화별 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
    List<MarginItem> getMargin(Account account) {
        return registry.require(toBrokerRef(account), MarginPort.class).getMargin(toBrokerRef(account));
    }

    // broker 모듈 순환 방지 — Account → BrokerAccountRef 변환 (broker는 Account를 직접 참조하지 않음)
    // Account.Broker → BrokerAccountRef.Broker는 상수명 byte-identical이라 valueOf(name())으로 매핑
    private static BrokerAccountRef toBrokerRef(Account account) {
        return new BrokerAccountRef(
                account.id(), account.appKey(), account.secretKey(),
                account.accountNo(), account.brokerAccountCode(),
                BrokerAccountRef.Broker.valueOf(account.broker().name()));
    }
}
