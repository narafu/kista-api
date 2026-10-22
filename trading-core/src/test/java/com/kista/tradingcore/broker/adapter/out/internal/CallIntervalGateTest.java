package com.kista.tradingcore.broker.adapter.out.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CallIntervalGate — 키별 최소 호출 간격")
class CallIntervalGateTest {

    // 거부 사유를 그대로 메시지에 담는 예외 팩토리
    private static final Function<String, RuntimeException> REJECT = IllegalStateException::new;

    @Test
    @DisplayName("같은 키의 두 번째 호출은 간격만큼 대기한다")
    void second_call_waits_at_least_interval() {
        CallIntervalGate gate = new CallIntervalGate(100, 20_000);
        gate.await("k", REJECT);

        long start = System.nanoTime();
        gate.await("k", REJECT);

        assertThat((System.nanoTime() - start) / 1_000_000L).isGreaterThanOrEqualTo(90);
    }

    @Test
    @DisplayName("대기 상한 초과 시 슬롯을 커밋하지 않고 즉시 거부한다 — 반복 거부가 대기열을 누적시키지 않음")
    void rejects_without_committing_slot_when_wait_exceeds_max() {
        CallIntervalGate gate = new CallIntervalGate(1000, 500);
        gate.await("k", REJECT);

        long start = System.nanoTime();
        assertThatThrownBy(() -> gate.await("k", REJECT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gate.await("k", REJECT)).isInstanceOf(IllegalStateException.class);
        assertThat((System.nanoTime() - start) / 1_000_000L).isLessThan(200);
    }

    @Test
    @DisplayName("다른 키끼리는 서로 기다리지 않는다")
    void different_keys_do_not_wait() {
        CallIntervalGate gate = new CallIntervalGate(1000, 20_000);
        gate.await("a", REJECT);

        long start = System.nanoTime();
        gate.await("b", REJECT);

        assertThat((System.nanoTime() - start) / 1_000_000L).isLessThan(200);
    }

    @Test
    @DisplayName("대기 중 인터럽트되면 플래그를 복원하고 거부 예외를 던진다")
    void interrupt_restores_flag_and_rejects() {
        CallIntervalGate gate = new CallIntervalGate(1000, 20_000);
        gate.await("k", REJECT);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> gate.await("k", REJECT)).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 다른 테스트 오염 방지
        }
    }
}
