package com.kista.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

// 스케쥴러 공통 실행 골격 — "STARTED 이벤트 → try(contexts 빌드 → 실행) → 인터럽트/예외 처리 → COMPLETED/FAILED 이벤트"
// notify 직접 호출 대신 SchedulerLifecycleEvent를 발행하고 notify가 SchedulerNotifier로 구독한다
// (모듈 순환 제거 — market/privacy/stats AlertNotifier와 동일 패턴). com.kista.platform.scheduling으로 이동 예정.
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerJobRunner {

    private final ApplicationEventPublisher events;

    // BatchContext 없이 단순 Runnable 작업 실행 — FearGreed·MarketCalendar 스케쥴러용
    public void run(String name, Runnable job) {
        events.publishEvent(SchedulerLifecycleEvent.started(name));
        log.info("{} 시작", name);
        try {
            job.run();
            log.info("{} 완료", name);
            events.publishEvent(SchedulerLifecycleEvent.completed(name));
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            events.publishEvent(SchedulerLifecycleEvent.failed(name, e));
        }
    }

    // name: 스케쥴러 표시명 (e.g., "장 개시 스케쥴러", "마감 매매 스케쥴러 수동")
    // contexts 타입은 호출 모듈 소유 — 골격은 size()만 로그에 쓰고 그대로 Action에 넘긴다 (모듈 경계상 제네릭)
    public <T> void run(String name, Supplier<List<T>> contextSupplier, Action<T> action) throws InterruptedException {
        events.publishEvent(SchedulerLifecycleEvent.started(name));
        try {
            List<T> contexts = contextSupplier.get(); // try 안 — 조회 실패도 FAILED로 잡히도록
            log.info("{} 시작 — 대상 {}개", name, contexts.size());
            action.accept(contexts);
            log.info("{} 완료", name);
            events.publishEvent(SchedulerLifecycleEvent.completed(name));
        } catch (InterruptedException e) {
            // 배포·재기동 강제 종료 — 이벤트 발행(동기) 후 rethrow해 SchedulerLockService가 락을 즉시 해제하도록 함
            log.warn("{} 인터럽트: {}", name, e.getMessage());
            events.publishEvent(SchedulerLifecycleEvent.failed(name, e));
            throw e;
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            events.publishEvent(SchedulerLifecycleEvent.failed(name, e));
        }
    }

    @FunctionalInterface
    public interface Action<T> {
        void accept(List<T> contexts) throws Exception;
    }
}
