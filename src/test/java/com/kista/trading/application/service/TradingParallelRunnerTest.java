package com.kista.trading.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradingParallelRunnerTest {

    @Test
    void runAll_결과는_제출_순서를_유지하고_empty는_제외한다() throws InterruptedException {
        TradingParallelRunner runner = new TradingParallelRunner(2);
        List<TradingParallelRunner.Task<Integer>> tasks = IntStream.range(0, 20)
                .mapToObj(i -> new TradingParallelRunner.Task<Integer>("acc-" + (i % 3),
                        () -> i % 5 == 0 ? Optional.empty() : Optional.of(i)))
                .toList();
        List<Integer> results = runner.runAll(tasks);
        assertThat(results).containsExactly(1, 2, 3, 4, 6, 7, 8, 9, 11, 12, 13, 14, 16, 17, 18, 19);
    }

    @Test
    void runAll_같은_그룹은_동시_실행_상한을_넘지_않는다() throws InterruptedException {
        TradingParallelRunner runner = new TradingParallelRunner(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger observedMax = new AtomicInteger();
        List<TradingParallelRunner.Task<Integer>> tasks = IntStream.range(0, 10)
                .mapToObj(i -> new TradingParallelRunner.Task<Integer>("same-account", () -> {
                    int now = current.incrementAndGet();
                    observedMax.accumulateAndGet(now, Math::max);
                    Thread.sleep(30);
                    current.decrementAndGet();
                    return Optional.of(i);
                }))
                .toList();
        assertThat(runner.runAll(tasks)).hasSize(10);
        assertThat(observedMax.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void runAll_다른_그룹은_동시에_실행된다() throws InterruptedException {
        TradingParallelRunner runner = new TradingParallelRunner(1);
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        List<TradingParallelRunner.Task<String>> tasks = List.of(
                new TradingParallelRunner.Task<>("acc-A", () -> {
                    aStarted.countDown();
                    return bStarted.await(2, TimeUnit.SECONDS) ? Optional.of("A") : Optional.empty();
                }),
                new TradingParallelRunner.Task<>("acc-B", () -> {
                    bStarted.countDown();
                    return aStarted.await(2, TimeUnit.SECONDS) ? Optional.of("B") : Optional.empty();
                }));
        assertThat(runner.runAll(tasks)).containsExactly("A", "B");
    }

    @Test
    void runAll_상한_0이하면_호출_스레드에서_순차_실행한다() throws InterruptedException {
        TradingParallelRunner runner = new TradingParallelRunner(0);
        Thread caller = Thread.currentThread();
        List<Thread> executedOn = new CopyOnWriteArrayList<>();
        List<TradingParallelRunner.Task<Integer>> tasks = IntStream.range(0, 3)
                .mapToObj(i -> new TradingParallelRunner.Task<Integer>("acc", () -> {
                    executedOn.add(Thread.currentThread());
                    return Optional.of(i);
                }))
                .toList();
        assertThat(runner.runAll(tasks)).containsExactly(0, 1, 2);
        assertThat(executedOn).allMatch(t -> t == caller);
    }

    @Test
    void runAll_격리되지_않은_예외는_IllegalStateException으로_승격한다() {
        TradingParallelRunner runner = new TradingParallelRunner(2);
        List<TradingParallelRunner.Task<Integer>> tasks = List.of(
                new TradingParallelRunner.Task<>("acc", () -> {
                    throw new RuntimeException("runSafely 밖으로 샌 예외");
                }));
        assertThrows(IllegalStateException.class, () -> runner.runAll(tasks));
    }
}
