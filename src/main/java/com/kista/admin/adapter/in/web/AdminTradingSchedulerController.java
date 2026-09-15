package com.kista.admin.adapter.in.web;

import com.kista.admin.application.port.output.TradingSchedulerCommandPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// 내부 API 호출이라 kista-api role에서도 항상 노출 가능 — 원격 trading-core 스케쥴러 존재 여부와
// 로컬 빈 게이팅이 무관해짐(web.AdminSchedulerController의 기존 @ConditionalOnProperty와 대비)
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/scheduler")
@RequiredArgsConstructor
public class AdminTradingSchedulerController {

    private final TradingSchedulerCommandPort schedulerCommandPort;

    @Operation(summary = "개장 스케쥴러 수동 트리거")
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerOpen() {
        schedulerCommandPort.triggerOpen();
    }

    @Operation(summary = "마감 스케쥴러 수동 트리거")
    @PostMapping("/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerClose() {
        schedulerCommandPort.triggerClose();
    }
}
