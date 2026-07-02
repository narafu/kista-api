package com.kista.adapter.in.schedule;

import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

// 스케쥴러 공통 실행 골격 — "알림 시작 → contexts 빌드 → try 실행 → 인터럽트/예외 처리 → 알림 완료"
@Slf4j
@Component
@RequiredArgsConstructor
class SchedulerJobRunner {

    private final NotifyPort notifyPort;

    // name: 스케쥴러 표시명 (e.g., "장 개시 스케쥴러", "마감 매매 스케쥴러 수동")
    void run(String name, Supplier<List<BatchContext>> contextSupplier, Action action) {
        notifyPort.notifyInfo(name + " 시작");
        List<BatchContext> contexts = contextSupplier.get();
        log.info("{} 시작 — ACTIVE 전략 {}개", name, contexts.size());
        try {
            action.accept(contexts);
            log.info("{} 완료", name);
            notifyPort.notifyInfo(name + " 완료");
        } catch (InterruptedException e) {
            // 배포·재기동으로 인한 강제 종료 — PLANNED 주문 접수 미실행 가능성 관리자 알림
            Thread.currentThread().interrupt();
            log.warn("{} 인터럽트: {}", name, e.getMessage());
            notifyPort.notifyError(e);
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            notifyPort.notifyError(e);
        }
    }

    @FunctionalInterface
    interface Action {
        void accept(List<BatchContext> contexts) throws Exception;
    }
}
