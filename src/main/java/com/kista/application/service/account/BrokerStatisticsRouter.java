package com.kista.application.service.account;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.broker.DailyTradePort;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.port.out.broker.PortfolioPort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// account.broker() 기반 통계 라우터 — BrokerAdapterRegistry 경유
@Slf4j
@Component
@RequiredArgsConstructor
class BrokerStatisticsRouter {

    private final BrokerAdapterRegistry registry;

    // 체결기준현재잔고 — KIS: CTRP6504R+TTTC2101R 보정 포함 / Toss: 보유종목+예수금 직접 산출
    PresentBalanceResult getPresentBalance(Account account) {
        return registry.require(account, PortfolioPort.class).getPresentBalance(account);
    }

    // 증거금 통화별 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
    List<MarginItem> getMargin(Account account) {
        return registry.require(account, MarginPort.class).getMargin(account);
    }

    // 판매 가능 수량 — KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity
    SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return registry.require(account, SellableQuantityPort.class).getSellableQuantity(ticker, account);
    }

    // 일별 거래내역 — KIS: CTOS4001R / Toss: execution+commission 조합 (accountId는 하위 호환 유지)
    DailyTransactionResult getDailyTransactions(UUID accountId, Account account, LocalDate from, LocalDate to) {
        return registry.require(account, DailyTradePort.class).getDailyTransactions(from, to, account);
    }
}
