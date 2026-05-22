package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.KisAccountPort;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.port.out.StrategyRepository;
import com.kista.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NextOrdersService implements GetNextOrdersUseCase {

    // 조회 전용 — 쓰기 포트(OrderPort, KisOrderPort 등) 의도적으로 미주입
    private final AccountRepository accountRepository;
    private final StrategyRepository strategyRepository; // 첫 번째 ACTIVE 전략 조회용
    private final KisAccountPort kisAccountPort;
    private final KisPricePort kisPricePort;
    private final TradingStrategy tradingStrategy;

    @Override
    @Transactional(readOnly = true)
    public Result preview(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);

        // 첫 번째 ACTIVE 전략 기준으로 미리보기
        Strategy strategy = strategyRepository.findByAccountId(accountId).stream()
                .filter(s -> s.status() == Strategy.StrategyStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("활성 전략이 없습니다: " + accountId));

        // 휴장 여부·잔고 shouldSkip() 무시 — 항상 강제 계산
        BigDecimal price = kisPricePort.getPrice(strategy.ticker(), account);
        InfinitePosition position = new InfinitePosition(
                kisAccountPort.getBalance(account, strategy.ticker()),
                strategy.ticker(), price, strategy.multiple());

        LocalDate today = LocalDate.now();
        List<Order> orders = tradingStrategy.buildOrders(position, today);
        log.info("[next-orders] accountId={}, ticker={}, orders={}, currentRound={}",
                accountId, strategy.ticker().name(), orders.size(), position.currentRound());

        return new Result(today, position, orders);
    }

}
