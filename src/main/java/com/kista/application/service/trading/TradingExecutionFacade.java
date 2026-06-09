package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.CancelResult;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TradingExecutionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// 매매 관련 서비스 4개를 TradingExecutionUseCase 단일 진입점으로 위임하는 Facade
// 트랜잭션 경계는 각 서비스(TradingService/OrderCancelService/TradingPreviewService)에 있으므로 이 클래스에 @Transactional 금지
@Service
@RequiredArgsConstructor
class TradingExecutionFacade implements TradingExecutionUseCase {

    private final TradingService tradingService;
    private final ManualTradingService manualTradingService;
    private final OrderCancelService orderCancelService;
    private final TradingPreviewService tradingPreviewService;

    @Override
    public void execute(TradingCycle cycle, Account account, User user) throws InterruptedException {
        tradingService.execute(cycle, account, user);
    }

    @Override
    public void executeBatch(List<BatchContext> contexts) throws InterruptedException {
        tradingService.executeBatch(contexts);
    }

    @Override
    public List<Order> executeManually(UUID cycleId, UUID requesterId) {
        return manualTradingService.execute(cycleId, requesterId);
    }

    @Override
    public CancelResult cancelByCycle(UUID cycleId, UUID requesterId) {
        return orderCancelService.cancelByCycle(cycleId, requesterId);
    }

    @Override
    public void cancelOrder(UUID orderId, UUID requesterId) {
        orderCancelService.cancelOrder(orderId, requesterId);
    }

    @Override
    public NextOrdersPreview preview(UUID accountId, UUID requesterId) {
        return tradingPreviewService.preview(accountId, requesterId);
    }
}
