package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.KisAccountPort;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NextOrdersService implements GetNextOrdersUseCase {

    // 조회 전용 — 쓰기 포트(PlannedOrderPort, KisOrderPort 등) 의도적으로 미주입
    private final AccountRepository accountRepository;
    private final KisAccountPort kisAccountPort;
    private final KisPricePort kisPricePort;
    private final TradingStrategy tradingStrategy;

    @Override
    @Transactional(readOnly = true)
    public Result preview(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);

        // 휴장 여부·잔고 shouldSkip() 무시 — 항상 강제 계산
        AccountBalance balance = kisAccountPort.getBalance(account);
        BigDecimal price = kisPricePort.getPrice(account.ticker(), account);
        InfinitePosition position = new InfinitePosition(balance, account.ticker(), price);

        LocalDate today = LocalDate.now();
        List<Order> orders = tradingStrategy.buildOrders(position, today);
        log.info("[next-orders] accountId={}, ticker={}, orders={}, currentRound={}",
                accountId, account.ticker().name(), orders.size(), position.currentRound());

        return new Result(today, position, orders);
    }

}
