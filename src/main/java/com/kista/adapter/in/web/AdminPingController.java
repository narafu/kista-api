package com.kista.adapter.in.web;

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

    // Phase 2A 가드 검증용 최소 엔드포인트 — Phase 2B에서 실제 admin 엔드포인트로 대체
    @GetMapping("/_ping")
    public PingResponse ping(@AuthenticationPrincipal UUID userId) {
        return new PingResponse("ok", userId.toString());
    }
}
