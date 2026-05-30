package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminDashboardResponse;
import com.kista.domain.port.in.AdminDashboardUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardUseCase dashboardUseCase;

    // 사용자 현황 통계 조회 (상태별 카운트 + 총 계좌 수)
    @Operation(summary = "대시보드 통계 조회")
    @GetMapping("/stats")
    public AdminDashboardResponse getStats(@AuthenticationPrincipal UUID adminId) {
        return AdminDashboardResponse.from(dashboardUseCase.getStats());
    }
}
