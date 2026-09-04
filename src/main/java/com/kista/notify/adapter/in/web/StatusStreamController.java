package com.kista.notify.adapter.in.web;

import com.kista.notify.adapter.out.sse.SseEmitterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

// 사용자 상태 변경(승인/거절) 실시간 SSE 스트림 — 경로는 /api/auth 유지 (kista-ui 계약 불변)
@Tag(name = "인증", description = "카카오 OAuth 로그인, 토큰 갱신, 사용자 정보 조회, 승인 신청")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class StatusStreamController {

    private final SseEmitterRegistry sseEmitterRegistry; // 사용자 상태 SSE 연결 등록

    // PENDING 상태 사용자의 SSE 연결 — 승인/거절 시 브라우저 자동 리다이렉트
    @Operation(summary = "승인 상태 SSE 스트림", description = "PENDING 상태 사용자가 연결. 관리자 승인/거절 시 이벤트 수신 후 브라우저 자동 이동.")
    @ApiResponse(responseCode = "200", description = "SSE 스트림 연결 성공")
    @GetMapping(value = "/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream(@AuthenticationPrincipal UUID userId) {
        return sseEmitterRegistry.connect(userId);
    }
}
