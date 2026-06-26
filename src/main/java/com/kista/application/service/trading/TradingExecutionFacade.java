package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.CancelResult;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.strategy.Strategy;
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
    public void execute(Strategy strategy, Account account, User user) throws InterruptedException {
        tradingService.execute(strategy, account, user);
    }

    @Override
    public void executeBatch(List<BatchContext> contexts) throws InterruptedException {
        tradingService.executeBatch(contexts);
    }

    @Override
    public void placeOpenOrders(List<BatchContext> contexts) throws InterruptedException {
        tradingService.placeOpenOrders(contexts);
    }

    @Override
    public void placeOpenOrdersNow(List<BatchContext> contexts) throws InterruptedException {
        tradingService.placeOpenOrders(contexts, DstInfo.immediateOpen());
    }

    @Override
    public void executeBatchNow(List<BatchContext> contexts) throws InterruptedException {
        tradingService.executeBatch(contexts, DstInfo.immediateClose());
    }

    @Override
    public List<Order> executeManually(UUID strategyId, UUID requesterId) {
        return manualTradingService.execute(strategyId, requesterId);
    }

    @Override
    public CancelResult cancelByCycle(UUID strategyId, UUID requesterId) {
        return orderCancelService.cancelByCycle(strategyId, requesterId);
    }

    @Override
    public void cancelOrder(UUID orderId, UUID requesterId) {
        orderCancelService.cancelOrder(orderId, requesterId);
    }

    @Override
    public NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        return tradingPreviewService.preview(strategyId, requesterId);
    }
}
