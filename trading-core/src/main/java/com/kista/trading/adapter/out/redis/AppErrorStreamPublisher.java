package com.kista.trading.adapter.out.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreams;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// trading-core에서 보고된 AppErrorRaisedEvent를 stream:app.error에 XADD — root admin(AppErrorStreamConsumer)이
// app_error_logs에 저장한다. 과거 TradingExceptionHandler가 root를 동기 HTTP(POST /api/internal/errors)로 호출하던
// 역방향 의존을 내구성 있는 push로 대체한 것 — root가 내려가 있어도 오류 보고가 유실되지 않고, 실패 응답을
// root 응답 대기로 붙잡지 않는다. Redis 장애 시엔 로그만 남기고 삼킨다(오류 보고 실패가 원래 응답을 막지 않도록).
@Slf4j
@Component
@RequiredArgsConstructor
class AppErrorStreamPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // 예외 처리기·알림 어댑터 등 트랜잭션 밖에서 발행되므로 일반 @EventListener로 동기 수신한다
    @EventListener
    public void onAppErrorRaised(AppErrorRaisedEvent event) {
        try {
            RedisStreams.add(redisTemplate, RedisStreamConfig.APP_ERROR_STREAM, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("오류 보고 스트림 발행 실패: {}", e.getMessage());
        }
    }
}
