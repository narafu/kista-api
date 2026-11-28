package com.kista.api.benchmark.adapter.in.schedule;

import com.kista.shared.platform.scheduling.SchedulerJobRunner;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.shared.kernel.TimeZones;
import com.kista.api.benchmark.application.service.MarketIndexPriceSyncService;
import lombok.RequiredArgsConstructor;
import com.kista.shared.platform.role.ConditionalOnWorkerRole;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@ConditionalOnWorkerRole
public class MarketIndexPriceSyncScheduler {

    private final MarketIndexPriceSyncService marketIndexPriceSyncService;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerLockService schedulerLockService;

    // 매일 09:00 KST — 미국 장마감(EDT 기준 KST 05:00, EST 기준 KST 06:00)보다 충분히 뒤라
    // 표준시 구간에도 통합(sip) 종가 확정 시각(뉴욕 16:16)을 넉넉히 넘기고, 비거래일엔 Alpaca가 빈 배열을 반환하는
    // 무해한 no-op이라 요일 조건 없이 매일 실행
    @Scheduled(cron = "0 0 9 * * *", zone = TimeZones.KST_ID)
    public void run() throws InterruptedException {
        schedulerLockService.tryRun("market-index-price-sync", Duration.ofMinutes(30), this::runLocked);
    }

    // 수동 트리거 — 크론 대기 없이 즉시 실행. run()과 락 이름을 공유해 크론과 동시 실행되지 않음
    public void runNow() throws InterruptedException {
        schedulerLockService.tryRun("market-index-price-sync", Duration.ofMinutes(30),
                () -> jobRunner.run("벤치마크 ETF 지수 종가 동기화 스케쥴러 수동", marketIndexPriceSyncService::syncAndSave));
    }

    private void runLocked() {
        jobRunner.run("벤치마크 ETF 지수 종가 동기화 스케쥴러", marketIndexPriceSyncService::syncAndSave);
    }
}
