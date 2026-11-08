package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.tradingcore.trading.adapter.in.schedule.TradingCloseScheduler;
import com.kista.tradingcore.trading.adapter.in.schedule.TradingOpenScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// admin(root)의 AdminTradingSchedulerController가 X-Internal-Token으로 호출하는 내부 트리거 엔드포인트 —
// root가 trading-core 스케쥴러 빈을 컴파일 의존 없이 원격으로 실행하게 한다
// 스케쥴러 빈과 같은 게이트 — scheduler.enabled=false(local)면 주입 대상이 없어 컨트롤러도 함께 끈다(엔드포인트 404)
@Slf4j
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/scheduler")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
public class TradingSchedulerInternalController {

    private final TradingOpenScheduler openScheduler;
    private final TradingCloseScheduler closeScheduler;

    private interface InterruptibleAction {
        void run() throws InterruptedException;
    }

    // 마감은 접수 창 밖이면 실행 단계에서 조용히 거부하고 관리자 알림만 보낸다(개장 runNow는 창 판정 없음) — 응답은 항상 202(CQ-60 현행 유지)
    // 백그라운드 가상 스레드 실행 + 인터럽트/예외 처리 — 202 반환 후 처리
    private void triggerAsync(String label, InterruptibleAction action) {
        Thread.ofVirtual().start(() -> {
            try {
                action.run();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                log.warn("{} 수동 트리거 인터럽트", label);
            } catch (Exception e) {
                log.error("{} 수동 트리거 오류: {}", label, e.getMessage(), e);
            }
        });
    }

    @Operation(summary = "개장 스케쥴러 수동 실행", description = "admin 수동 트리거 — 202 후 백그라운드 실행. X-Internal-Token 필수.")
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerOpen() {
        triggerAsync("개장 스케쥴러", openScheduler::runNow);
    }

    @Operation(summary = "마감 스케쥴러 수동 실행", description = "admin 수동 트리거 — 202 후 백그라운드 실행. X-Internal-Token 필수.")
    @PostMapping("/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerClose() {
        triggerAsync("마감 스케쥴러", closeScheduler::runNow);
    }
}
