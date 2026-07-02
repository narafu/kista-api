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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} 인터럽트: {}", name, e.getMessage());
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            notifyPort.notifyError(e);
        }
        log.info("{} 완료", name);
        notifyPort.notifyInfo(name + " 완료");
    }

    @FunctionalInterface
    interface Action {
        void accept(List<BatchContext> contexts) throws Exception;
    }
}
