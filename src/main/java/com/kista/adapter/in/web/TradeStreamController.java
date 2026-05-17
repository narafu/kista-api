package com.kista.adapter.in.web;

import com.kista.adapter.out.sse.TradeSseEmitterRegistry;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

// 실시간 매매 알림 SSE 스트리밍 엔드포인트
@Tag(name = "Trade Stream", description = "실시간 매매 알림 SSE")
@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeStreamController {

    private final TradeSseEmitterRegistry tradeSseEmitterRegistry;

    // GET /api/trades/stream — 인증된 사용자의 매매 알림 스트림
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@AuthenticationPrincipal UUID userId) {
        return tradeSseEmitterRegistry.connect(userId);
    }
}
