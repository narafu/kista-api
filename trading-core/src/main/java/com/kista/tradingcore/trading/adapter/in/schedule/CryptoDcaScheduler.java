package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.trading.application.port.output.HeartbeatPort;
import com.kista.tradingcore.trading.application.usecase.CryptoDcaExecutionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

// 코인 DCA 10분 주기 틱 — SchedulerJobRunner는 시작/종료 알림이 하루 144회 나가므로 쓰지 않고 락만 건다
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 운영 DB·텔레그램과 중복 방지
public class CryptoDcaScheduler {

    private final CryptoDcaExecutionUseCase useCase;
    private final SchedulerLockService schedulerLockService;
    private final HeartbeatPort heartbeatPort; // dead-man's switch 핑
    Clock clock = Clock.system(TimeZones.KST); // 테스트에서 고정 Clock으로 교체

    @Scheduled(cron = "0 */10 * * * *", zone = TimeZones.KST_ID) // 10분마다 KST 정각 기준
    public void run() throws InterruptedException {
        boolean completed = schedulerLockService.tryRun("crypto-dca", Duration.ofMinutes(9),
                () -> useCase.runTick(LocalDateTime.now(clock.withZone(TimeZones.KST))));
        // 틱 완료 시에만 — 락 미획득·예외면 미발송. 계좌별 실패는 러너가 관리자 알림으로 따로 처리
        if (completed) heartbeatPort.pingCrypto();
    }
}
