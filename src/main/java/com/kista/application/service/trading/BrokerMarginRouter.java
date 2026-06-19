package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.TosMarginPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// USD 매수가능금액을 브로커 무관하게 단일 인터페이스로 제공
@Component
@RequiredArgsConstructor
public class BrokerMarginRouter {

    private final KisMarginPort kisMarginPort;
    private final TosMarginPort tosMarginPort;

    public BigDecimal getUsdBuyableAmount(Account account) {
        return switch (account.broker()) {
            case KIS -> kisMarginPort.getMargin(account).stream()
                    .filter(m -> Currency.USD == m.currency())
                    .findFirst()
                    .map(m -> m.purchasableAmount())
                    .orElse(BigDecimal.ZERO);
            // Toss: USD 예수금만 주문 가능 — KRW는 미국주식 주문에 자동환전 안 됨
            case TOSS -> tosMarginPort.getBuyableAmount(account);
        };
    }
}
