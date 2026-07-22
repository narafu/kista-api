package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.CancelResult;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// 매매 실행·취소·미리보기 통합 인터페이스 (Facade 위임)
public interface TradingExecutionUseCase {
    // 스케쥴러 — 단건 실행 (전략 + 계좌 + 유저 묶음)
    void execute(Strategy strategy, Account account, User user) throws InterruptedException;
    // 스케쥴러 — 배치 실행
    void executeBatch(List<BatchContext> contexts) throws InterruptedException;
    // 개장 스케쥴러 — 전략 order 생성 + INFINITE 매도 선접수 + 예수금 부족 시 사용자 알람
    void placeOpenOrders(List<BatchContext> contexts) throws InterruptedException;
    // 개장 스케쥴러 수동 트리거 — 개장 대기 없이 즉시 실행
    void placeOpenOrdersNow(List<BatchContext> contexts) throws InterruptedException;
    // 마감 스케쥴러 수동 트리거 — 주문 대기 없이 즉시 실행
    void executeBatchNow(List<BatchContext> contexts) throws InterruptedException;
    // 수동 실행 (INFINITE 전용)
    List<Order> executeManually(UUID strategyId, UUID requesterId);
    // 전략 주문 전체 취소 (오늘 PLANNED + PLACED, best-effort)
    CancelResult cancelByCycle(UUID strategyId, UUID requesterId);
    // 특정 주문 1건 취소
    void cancelOrder(UUID orderId, UUID requesterId);
    // 다음 주문 미리보기 (DB 저장 없음) — strategyId 기준
    NextOrdersPreview preview(UUID strategyId, UUID requesterId);
    // 계좌 내 전략 전체 다음 주문 미리보기 일괄 조회 (DB 저장 없음) — strategyId → preview 맵, 사이클 없는 전략은 생략
    Map<UUID, NextOrdersPreview> previewBatch(UUID accountId, UUID requesterId);
}
