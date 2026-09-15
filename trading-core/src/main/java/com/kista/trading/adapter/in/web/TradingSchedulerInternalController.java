package com.kista.trading.adapter.in.web;

import com.kista.trading.adapter.in.schedule.TradingCloseScheduler;
import com.kista.trading.adapter.in.schedule.TradingOpenScheduler;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// admin(root)의 AdminTradingSchedulerController가 X-Internal-Token으로 호출하는 내부 트리거 엔드포인트 —
// root가 trading-core 스케쥴러 빈을 컴파일 의존 없이 원격으로 실행하게 한다
@Slf4j
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/scheduler")
@RequiredArgsConstructor
public class TradingSchedulerInternalController {

    private final TradingOpenScheduler openScheduler;
    private final TradingCloseScheduler closeScheduler;

    private interface InterruptibleAction {
        void run() throws InterruptedException;
    }

    // 백그라운드 가상 스레드 실행 + 인터럽트/예외 처리 — 202 반환 후 처리
    private void triggerAsync(String label, InterruptibleAction action) {
        Thread.ofVirtual().start(() -> {
            try {
                action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{} 수동 트리거 인터럽트", label);
            } catch (Exception e) {
                log.error("{} 수동 트리거 오류: {}", label, e.getMessage(), e);
            }
        });
    }

    @PostMapping("/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerOpen() {
        triggerAsync("개장 스케쥴러", openScheduler::runNow);
    }

    @PostMapping("/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerClose() {
        triggerAsync("마감 스케쥴러", closeScheduler::runNow);
    }
}
