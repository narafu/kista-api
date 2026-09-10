package com.kista.trading.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.domain.model.BatchContext;

import java.util.List;
import java.util.Optional;

// 전략별 단계 실행 격리 가드 — 실패 시 로그+관리자/사용자 알림 후 Optional.empty() 반환 (InterruptedException은 예외적으로 재throw)
@Component
@RequiredArgsConstructor
@Slf4j
class TradingBatchGuard {

    private final ApplicationEventPublisher eventPublisher;

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    <T> Optional<T> runSafely(String phase, BatchContext ctx, ThrowingSupplier<T> supplier) throws InterruptedException {
        try {
            return Optional.ofNullable(supplier.get());
        } catch (InterruptedException e) {
            throw e; // InterruptedException은 삼키지 않음
        } catch (Exception e) {
            log.error("[strategyId={}] {} 오류: {}", ctx.strategy().id(), phase, e.getMessage(), e);
            notifyErrorSafely(ctx, e);
            return Optional.empty();
        }
    }

    void notifyErrorSafely(BatchContext ctx, Exception e) {
        try {
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 관리자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
        try {
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user().id(), e.getMessage()));
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 사용자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
    }

    // 인터럽트 시점에 아직 증권사 접수가 안 된 전략들에게 알림 (증권사 접수 완료된 전략은 대상 아님)
    void notifyBatchInterrupted(List<BatchContext> contexts) {
        contexts.forEach(ctx -> {
            try {
                eventPublisher.publishEvent(new BatchInterruptedEvent(ctx.user().id(), ctx.account().id()));
            } catch (Exception notifyEx) {
                log.warn("[strategyId={}] 인터럽트 알림 발송 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
            }
        });
    }
}
