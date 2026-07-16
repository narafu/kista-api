package com.kista.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "관리자 API")
public class AdminPingController {

    record PingResponse(String status, String adminId) {} // 관리자 ping 응답

    // hasRole("ADMIN") 가드 동작 확인용 최소 엔드포인트 — AdminPingControllerTest 패턴 참고
    @Operation(summary = "ADMIN 권한 확인용 핑", description = "hasRole(\"ADMIN\") 가드 동작 확인용 최소 엔드포인트입니다.")
    @GetMapping("/_ping")
    public PingResponse ping(@AuthenticationPrincipal UUID userId) {
        return new PingResponse("ok", userId.toString());
    }
}
