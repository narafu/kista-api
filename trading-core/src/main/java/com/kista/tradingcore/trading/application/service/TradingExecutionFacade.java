package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.domain.model.CancelResult;
import com.kista.tradingcore.trading.domain.model.NextOrdersPreview;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 매매 관련 서비스 4개를 TradingExecutionFacade 단일 진입점으로 위임하는 Facade
// 트랜잭션 경계는 각 서비스(TradingService/OrderCancelService/TradingPreviewService)에 있으므로 이 클래스에 @Transactional 금지
@Service
@RequiredArgsConstructor
public class TradingExecutionFacade {

    private final TradingService tradingService;
    private final ManualTradingService manualTradingService;
    private final OrderCancelService orderCancelService;
    private final TradingPreviewService tradingPreviewService;
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    // 스케쥴러 — 배치 실행
    public void executeBatch(List<BatchContext> contexts) throws InterruptedException {
        tradingService.executeBatch(contexts);
    }

    // 개장 스케쥴러 — 전략 order 생성 + INFINITE 지정가 매도 선접수 + 예수금 부족 시 사용자 알람
    public void placeOpenOrders(List<BatchContext> contexts) throws InterruptedException {
        tradingService.placeOpenOrders(contexts);
    }

    // 개장 스케쥴러 수동 트리거 — 개장 대기 없이 즉시 실행
    public void placeOpenOrdersNow(List<BatchContext> contexts) throws InterruptedException {
        tradingService.placeOpenOrders(contexts, DstInfo.immediateOpen(ZonedDateTime.now(clock)));
    }

    // 마감 스케쥴러 수동 트리거 — 주문 대기 없이 즉시 실행
    public void executeBatchNow(List<BatchContext> contexts) throws InterruptedException {
        tradingService.executeBatch(contexts, tradingService.closeSession().withImmediateOrder(clock.instant()));
    }

    // 재기동 재개 — 마감 배치 접수 완료 이후 중단분 리포트
    public void resumeCloseReport(List<BatchContext> contexts) throws InterruptedException {
        tradingService.resumeCloseReport(contexts, tradingService.closeSession());
    }

    // 수동 실행 (DCA는 배치 전용이라 거부)
    public List<Order> executeManually(UUID strategyId, UUID requesterId) {
        return manualTradingService.execute(strategyId, requesterId);
    }

    // 전략 주문 전체 취소 (오늘 PLANNED + PLACED, best-effort)
    public CancelResult cancelByCycle(UUID strategyId, UUID requesterId) {
        return orderCancelService.cancelByCycle(strategyId, requesterId);
    }

    // 특정 주문 1건 취소
    public void cancelOrder(UUID orderId, UUID requesterId) {
        orderCancelService.cancelOrder(orderId, requesterId);
    }

    // 다음 주문 미리보기 (DB 저장 없음) — strategyId 기준
    public NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        return tradingPreviewService.preview(strategyId, requesterId);
    }

    // 계좌 내 전략 전체 다음 주문 미리보기 일괄 조회 (DB 저장 없음) — strategyId → preview 맵, 사이클 없는 전략은 생략
    public Map<UUID, NextOrdersPreview> previewBatch(UUID accountId, UUID requesterId) {
        return tradingPreviewService.previewBatch(accountId, requesterId);
    }
}
