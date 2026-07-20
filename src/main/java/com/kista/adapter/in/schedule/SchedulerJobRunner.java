package com.kista.adapter.in.schedule;

import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

// 스케쥴러 공통 실행 골격 — "알림 시작 → try(contexts 빌드 → 실행) → 인터럽트/예외 처리 → 알림 완료"
@Slf4j
@Component
@RequiredArgsConstructor
class SchedulerJobRunner {

    private final NotifyPort notifyPort;

    // BatchContext 없이 단순 Runnable 작업 실행 — FearGreed·MarketCalendar 스케쥴러용
    void run(String name, Runnable job) {
        notifyPort.notifyInfo(name + " 시작");
        log.info("{} 시작", name);
        try {
            job.run();
            log.info("{} 완료", name);
            notifyPort.notifyInfo(name + " 완료");
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            notifyPort.notifyError(e);
        }
    }

    // name: 스케쥴러 표시명 (e.g., "장 개시 스케쥴러", "마감 매매 스케쥴러 수동")
    void run(String name, Supplier<List<BatchContext>> contextSupplier, Action action) throws InterruptedException {
        notifyPort.notifyInfo(name + " 시작");
        try {
            List<BatchContext> contexts = contextSupplier.get(); // try 안으로 이동 — 조회 자체가 실패해도 오류 알림 누락 없이 잡히도록
            log.info("{} 시작 — ACTIVE 전략 {}개", name, contexts.size());
            action.accept(contexts);
            log.info("{} 완료", name);
            notifyPort.notifyInfo(name + " 완료");
        } catch (InterruptedException e) {
            // 배포·재기동 강제 종료 — 알림 후 rethrow해 SchedulerLockService가 락을 즉시 해제하도록 함
            // (삼키면 tryRun이 성공으로 간주 → 락 2~3h 잔류 → 관리자 runNow 재실행 불가)
            log.warn("{} 인터럽트: {}", name, e.getMessage());
            notifyPort.notifyError(e); // rethrow 전에 IO 완료
            throw e;
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
