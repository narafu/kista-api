package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.port.out.BrokerMarginPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.TosMarginPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

// account.broker() 기반 증거금 조회 라우팅 — KisMarginPort/TosMarginPort 단일 진입점
@Component
@RequiredArgsConstructor
public class BrokerMarginRouter implements BrokerMarginPort {

    private final KisMarginPort kisMarginPort;
    private final TosMarginPort tosMarginPort;

    @Override
    public List<MarginItem> getMargin(Account account) {
        return switch (account.broker()) {
            case KIS -> kisMarginPort.getMargin(account);
            case TOSS -> tosMarginPort.getMarginItems(account);
        };
    }

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return switch (account.broker()) {
            case KIS -> kisMarginPort.getUsdBuyableAmount(account);
            case TOSS -> tosMarginPort.getBuyableAmount(account);
        };
    }
}
