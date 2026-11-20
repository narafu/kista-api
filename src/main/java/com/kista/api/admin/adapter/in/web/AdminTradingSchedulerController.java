package com.kista.api.admin.adapter.in.web;

import com.kista.api.admin.application.port.output.TradingSchedulerCommandPort;
import com.kista.shared.platform.role.ConditionalOnWorkerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// 개장/마감 수동 트리거 — worker에서만 등록(web.AdminSchedulerController와 같은 @ConditionalOnWorkerRole).
// Caddy가 /api/admin/scheduler/**를 worker로 보내므로 web에 두면 RoleRoutingGuard가 503으로 거절한다. worker에서는 INTERNAL_API_BASE_URL(localhost:8080)로 자기 내부 API를 호출한다
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/scheduler")
@RequiredArgsConstructor
@ConditionalOnWorkerRole
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
