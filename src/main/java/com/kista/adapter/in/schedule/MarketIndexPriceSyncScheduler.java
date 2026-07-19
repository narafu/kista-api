package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.port.in.SyncMarketIndexPricesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
public class MarketIndexPriceSyncScheduler {

    private final SyncMarketIndexPricesUseCase syncMarketIndexPricesUseCase;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    // 매일 06:00 KST — TradingCloseScheduler(화~토 04:30 KST)보다 충분히 뒤라 IEX 종가 확정 시간을 벌고,
    // 비거래일엔 Alpaca가 빈 배열을 반환하는 무해한 no-op이라 요일 조건 없이 매일 실행
    @Scheduled(cron = "0 0 6 * * *", zone = TimeZones.KST_ID)
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("market-index-price-sync", Duration.ofMinutes(30), this::runLocked);
    }

    private void runLocked() {
        jobRunner.run("벤치마크 ETF 지수 종가 동기화 스케쥴러", syncMarketIndexPricesUseCase::syncAndSave);
    }
}
