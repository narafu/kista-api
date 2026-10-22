package com.kista.tradingcore.broker.adapter.out.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

// 키(앱키 등) 단위 최소 호출 간격 게이트 — 락 없는 슬롯 예약(CAS 루프로 슬롯만 확정하고 대기는 락 밖에서
// 하므로 같은 키 호출끼리 sleep 동안 서로를 막지 않는다). System.nanoTime()은 상대 경과 시간
// 측정용이라 NTP 보정 등 벽시계 역행에 영향받지 않는다.
// 대기 상한 초과로 거부하는 호출은 슬롯을 커밋하지 않는다 — accumulateAndGet처럼 무조건 먼저 예약해버리면
// 거부된 호출도 대기열을 뒤로 밀어버려 반복 거부가 대기열을 무한히 미래로 누적시키는 회귀가 생긴다.
public final class CallIntervalGate {

    private final long intervalNanos; // 같은 키의 연속 호출 사이 최소 간격
    private final long maxWaitNanos;  // 이 이상 기다려야 하면 대기하지 않고 즉시 거부
    private final ConcurrentHashMap<String, AtomicLong> nextSlotByKey = new ConcurrentHashMap<>();

    public CallIntervalGate(long minIntervalMillis, long maxWaitMillis) {
        this.intervalNanos = minIntervalMillis * 1_000_000L;
        this.maxWaitNanos = maxWaitMillis * 1_000_000L;
    }

    // 슬롯 확보까지 대기 — 대기 상한 초과·인터럽트 시 onReject(사유)가 만든 예외를 던진다
    public void await(String key, Function<String, RuntimeException> onReject) {
        AtomicLong nextSlot = nextSlotByKey.computeIfAbsent(key, _ -> new AtomicLong(0));
        while (true) {
            long now = System.nanoTime();
            long currentSlot = nextSlot.get();
            long myTurn = Math.max(currentSlot, now);
            long waitNanos = myTurn - now;
            if (waitNanos > maxWaitNanos) {
                throw onReject.apply((waitNanos / 1_000_000L) + "ms 대기 필요");
            }
            if (!nextSlot.compareAndSet(currentSlot, myTurn + intervalNanos)) {
                continue; // 동시 경쟁으로 CAS 실패 — 최신 슬롯으로 재계산
            }
            if (waitNanos <= 0) return;
            try {
                Thread.sleep(waitNanos / 1_000_000L);
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw onReject.apply("대기 중 인터럽트");
            }
        }
    }
}
