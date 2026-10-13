package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.usecase.DcaExecutionQuery;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaExecutionPage;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class DcaExecutionQueryService implements DcaExecutionQuery {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final DcaExecutionPort dcaExecutionPort;
    private final OrderPort orderPort;
    private final MarketCalendarPort marketCalendarPort;

    @Override
    @Transactional(readOnly = true)
    public DcaExecutionPage list(UUID strategyId, UUID requesterId, int page, int size) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        if (!strategy.isDca()) throw new IllegalArgumentException("DCA 전략이 아닙니다: " + strategyId);

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        List<DcaExecution> executions = dcaExecutionPort.findPage(strategyId, safePage, safeSize);
        long total = dcaExecutionPort.count(strategyId);

        // ORDERED 회차의 주문을 1회 조회로 합친다
        List<UUID> orderIds = executions.stream().map(DcaExecution::orderId).filter(Objects::nonNull).toList();
        Map<UUID, Order> orders = orderIds.isEmpty() ? Map.of()
                : orderPort.findAllByIds(orderIds).stream().collect(Collectors.toMap(Order::id, Function.identity()));

        List<DcaExecutionPage.Row> rows = executions.stream()
                .map(e -> new DcaExecutionPage.Row(e, marketCalendarPort.usSessionDateOf(e.tradeDate()),
                        e.orderId() == null ? null : orders.get(e.orderId())))
                .toList();
        return new DcaExecutionPage(rows, safePage, safeSize, total);
    }
}
