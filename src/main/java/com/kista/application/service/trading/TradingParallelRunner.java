package com.kista.application.service.trading;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

// 사이클 단위 배치 작업을 가상 스레드로 병렬 실행 — 그룹(계좌)별 Semaphore로 브로커 rate limit·Toss 토큰 경합 제어
@Slf4j
@Component
class TradingParallelRunner {

    private final int maxConcurrentPerGroup; // 그룹별 동시 실행 상한 — 0 이하면 순차 인라인 실행(단위 테스트·긴급 롤백용)

    TradingParallelRunner(@Value("${app.trading.parallel-per-account:2}") int maxConcurrentPerGroup) {
        this.maxConcurrentPerGroup = maxConcurrentPerGroup;
    }

    // groupKey: 동시 상한을 공유하는 단위(계좌 id) / action: runSafely로 감싼 사이클 작업 — 예외는 action 내부에서 격리되는 것이 계약
    record Task<T>(Object groupKey, Callable<Optional<T>> action) {}

    // 제출 순서대로 결과를 수집한다(Optional.empty 제외) — 기존 순차 for 루프의 결과 순서 계약 유지
    <T> List<T> runAll(List<Task<T>> tasks) throws InterruptedException {
        if (tasks.isEmpty()) return List.of();
        if (maxConcurrentPerGroup <= 0) return runSequentially(tasks);

        Map<Object, Semaphore> gates = new ConcurrentHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Optional<T>>> callables = tasks.stream()
                    .map(task -> (Callable<Optional<T>>) () -> {
                        // 같은 계좌의 동시 호출 상한 — KIS rate limit·Toss 공유 토큰 갱신 경합 완화
                        Semaphore gate = gates.computeIfAbsent(task.groupKey(),
                                ignored -> new Semaphore(maxConcurrentPerGroup));
                        gate.acquire();
                        try {
                            return task.action().call();
                        } finally {
                            gate.release();
                        }
                    })
                    .toList();
            // 호출 스레드 인터럽트 시 invokeAll이 미완료 작업을 취소·인터럽트한 뒤 InterruptedException 전파 — 기존 rethrow 계약과 동일
            List<Future<Optional<T>>> futures = executor.invokeAll(callables);
            List<T> results = new ArrayList<>();
            for (Future<Optional<T>> future : futures) {
                collectResult(future).ifPresent(results::add);
            }
            return results;
        }
    }

    private <T> Optional<T> collectResult(Future<Optional<T>> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // 워커 인터럽트를 호출 스레드 인터럽트로 승격
                throw new InterruptedException("병렬 사이클 작업 인터럽트");
            }
            // runSafely가 예외를 격리하므로 정상 경로에서는 도달하지 않음 — 이중 알림 없이 방어적 승격만 수행
            throw new IllegalStateException("병렬 사이클 작업 실패", e.getCause());
        }
    }

    // 상한 0 이하: 호출 스레드 인라인 순차 실행 — 기존 순차 동작과 동일해 TradingServiceTest 결정성 보장
    private <T> List<T> runSequentially(List<Task<T>> tasks) throws InterruptedException {
        List<T> results = new ArrayList<>();
        for (Task<T> task : tasks) {
            try {
                task.action().call().ifPresent(results::add);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("순차 사이클 작업 실패", e);
            }
        }
        return results;
    }
}
