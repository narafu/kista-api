package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.tradingcore.trading.application.port.output.HeartbeatPort;
import com.kista.tradingcore.trading.application.service.CryptoDcaRunner;
import lombok.RequiredArgsConstructor;
import com.kista.shared.platform.role.ConditionalOnWorkerRole;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

// 코인 DCA 10분 주기 틱 — SchedulerJobRunner는 시작/종료 알림이 하루 144회 나가므로 쓰지 않고 락만 건다
@Component
@RequiredArgsConstructor
@ConditionalOnWorkerRole // web role(kista-api)에서는 미등록 — 운영 DB·텔레그램 중복 실행 방지
public class CryptoDcaScheduler {

    private final CryptoDcaRunner useCase;
    private final SchedulerLockService schedulerLockService;
    private final HeartbeatPort heartbeatPort; // dead-man's switch 핑
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    @Scheduled(cron = "0 */10 * * * *", zone = TimeZones.KST_ID) // 10분마다 KST 정각 기준
    public void run() throws InterruptedException {
        boolean completed = schedulerLockService.tryRun("crypto-dca", Duration.ofMinutes(9),
                () -> useCase.runTick(LocalDateTime.now(clock)));
        // 틱 완료 시에만 — 락 미획득·예외면 미발송. 계좌별 실패는 러너가 관리자 알림으로 따로 처리
        if (completed) heartbeatPort.pingCrypto();
    }
}
