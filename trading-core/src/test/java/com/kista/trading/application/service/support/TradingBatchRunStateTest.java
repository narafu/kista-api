package com.kista.trading.application.service.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingBatchRunStateTest {

    @Test
    void requestStop_whileSleeping_interruptsImmediately() throws Exception {
        TradingBatchRunState state = new TradingBatchRunState();
        CountDownLatch sleeping = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread batch = Thread.ofVirtual().start(() -> {
            try {
                state.track(() -> {
                    sleeping.countDown();
                    state.sleep(Duration.ofHours(1));
                });
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        sleeping.await(5, TimeUnit.SECONDS);
        Thread.sleep(50); // sleep 진입 대기

        boolean stoppedCleanly = state.requestStop(Duration.ofSeconds(5));

        batch.join(5_000);
        assertThat(stoppedCleanly).isTrue();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertThat(state.isStopping()).isTrue();
    }

    @Test
    void requestStop_whileCritical_waitsUntilCriticalEnds_thenInterruptsNextSleep() throws Exception {
        TradingBatchRunState state = new TradingBatchRunState();
        CountDownLatch inCritical = new CountDownLatch(1);
        CountDownLatch releaseCritical = new CountDownLatch(1);
        AtomicBoolean criticalCompleted = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread batch = Thread.ofVirtual().start(() -> {
            try {
                state.track(() -> {
                    state.critical(() -> {
                        inCritical.countDown();
                        releaseCritical.await();
                        criticalCompleted.set(true);
                        return null;
                    });
                    state.sleep(Duration.ofHours(1)); // 임계구역 뒤 대기 — 종료 요청으로 즉시 중단돼야 함
                });
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        inCritical.await(5, TimeUnit.SECONDS);

        Thread stopper = Thread.ofVirtual().start(() -> state.requestStop(Duration.ofSeconds(10)));
        Thread.sleep(100);
        assertThat(criticalCompleted).isFalse(); // 임계구역은 인터럽트되지 않고 진행 중
        releaseCritical.countDown();

        stopper.join(5_000);
        batch.join(5_000);
        assertThat(criticalCompleted).isTrue();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    void requestStop_criticalExceedsLimit_returnsFalse() throws Exception {
        TradingBatchRunState state = new TradingBatchRunState();
        CountDownLatch inCritical = new CountDownLatch(1);
        CountDownLatch releaseCritical = new CountDownLatch(1);
        Thread batch = Thread.ofVirtual().start(() -> {
            try {
                state.track(() -> state.critical(() -> {
                    inCritical.countDown();
                    releaseCritical.await();
                    return null;
                }));
            } catch (InterruptedException ignored) {
            }
        });
        inCritical.await(5, TimeUnit.SECONDS);

        assertThat(state.requestStop(Duration.ofMillis(100))).isFalse();

        releaseCritical.countDown();
        batch.join(5_000);
    }

    @Test
    void sleep_afterStopRequestedWithoutBatch_throwsImmediately() {
        TradingBatchRunState state = new TradingBatchRunState();
        assertThat(state.requestStop(Duration.ZERO)).isTrue(); // 실행 중 배치 없음

        assertThatThrownBy(() -> state.sleep(Duration.ofHours(1))).isInstanceOf(InterruptedException.class);
    }
}
