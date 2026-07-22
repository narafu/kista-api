package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.broker.LiveBalancePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

// 계좌 단위 라이브 예수금(usdDeposit) 짧은 TTL 캐시 — preview 경쟁 시뮬레이션 전용(TradingBuyCompetitionSimulator)
// usdDeposit은 ticker와 무관하게 계좌 전체 값이라 계좌당 전략 N개가 preview를 병렬 호출해도 실제 조회는 1회면 충분함
// 실주문 집행 경로(ManualTradingService/TradingOrderBudgetAllocator)는 이 캐시를 사용하지 않고 항상 최신값을 직접 조회함
@Component
@RequiredArgsConstructor
class PreviewDepositCache {

    private static final Duration TTL = Duration.ofSeconds(3);

    private final BrokerAdapterRegistry registry;

    private final ConcurrentMap<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>(); // 계좌별 락 — 동시 miss가 N번 조회하는 것 방지

    private record Entry(BigDecimal usdDeposit, Instant expiresAt) {
        boolean isValid(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    BigDecimal getUsdDeposit(Account account, Ticker probeTicker) {
        Instant now = Instant.now();
        Entry cached = cache.get(account.id());
        if (cached != null && cached.isValid(now)) {
            return cached.usdDeposit();
        }

        ReentrantLock lock = locks.computeIfAbsent(account.id(), k -> new ReentrantLock());
        lock.lock();
        try {
            Entry doubleChecked = cache.get(account.id());
            if (doubleChecked != null && doubleChecked.isValid(Instant.now())) {
                return doubleChecked.usdDeposit();
            }
            BigDecimal fresh = registry.require(account, LiveBalancePort.class)
                    .getLiveBalance(account, probeTicker)
                    .usdDeposit();
            cache.put(account.id(), new Entry(fresh, Instant.now().plus(TTL)));
            return fresh;
        } finally {
            lock.unlock();
        }
    }
}
