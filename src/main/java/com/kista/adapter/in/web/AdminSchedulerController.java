package com.kista.adapter.in.web;

import com.kista.adapter.in.schedule.TradingCloseScheduler;
import com.kista.adapter.in.schedule.TradingOpenScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/admin/scheduler")
@Tag(name = "Admin", description = "관리자 API")
public class AdminSchedulerController {

    // @ConditionalOnProperty로 빈이 없을 수 있으므로 required=false
    @Autowired(required = false)
    private TradingOpenScheduler openScheduler;

    @Autowired(required = false)
    private TradingCloseScheduler closeScheduler;

    // 개장 스케쥴러 수동 트리거 — 개장 대기 없이 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "개장 스케쥴러 수동 트리거", description = "개장 대기 없이 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerOpen() {
        if (openScheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        Thread.ofVirtual().start(() -> {
            try {
                openScheduler.runNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("개장 스케쥴러 수동 트리거 인터럽트");
            } catch (Exception e) {
                log.error("개장 스케쥴러 수동 트리거 오류: {}", e.getMessage(), e);
            }
        });
    }

    // 마감 스케쥴러 수동 트리거 — 주문 대기 없이 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "마감 스케쥴러 수동 트리거", description = "주문 대기 없이 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerClose() {
        if (closeScheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        Thread.ofVirtual().start(() -> {
            try {
                closeScheduler.runNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("마감 스케쥴러 수동 트리거 인터럽트");
            } catch (Exception e) {
                log.error("마감 스케쥴러 수동 트리거 오류: {}", e.getMessage(), e);
            }
        });
    }
}
