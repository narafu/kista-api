package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.port.out.BrokerPortfolioPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.TossPortfolioPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// account.broker() 기반 포트폴리오 조회 라우팅 — KisPortfolioPort/TossPortfolioPort 단일 진입점
@Component
@RequiredArgsConstructor
public class BrokerPortfolioRouter implements BrokerPortfolioPort {

    private final KisPortfolioPort kisPortfolioPort;
    private final TossPortfolioPort tossPortfolioPort;

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        return switch (account.broker()) {
            case KIS -> kisPortfolioPort.getPresentBalance(account);
            case TOSS -> tossPortfolioPort.getPresentBalance(account);
        };
    }
}
