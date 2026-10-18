package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.application.event.CycleCompletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;

// fallbackExecution 알림 리스너가 트랜잭션 밖 발행 지점(CyclePositionPersistor 등)에서 인라인 실행될 때
// 알림 경로 실패(FCM 위임 XADD)가 발행자에게 전파되지 않는지 실 컨텍스트로 잠근다
@Tag("integration")
// DB 15 — 로컬 trading-core(DB 0)의 user.* 스트림 컨슈머 그룹과 경쟁하지 않도록 테스트 전용 DB(UserEventStreamBridgeIT와 동일)
@SpringBootTest(properties = "spring.data.redis.url=redis://localhost:6379/15")
// 실 Redis 스트림 구독자(user.* 컨슈머 그룹)가 살아 있는 컨텍스트 — 캐시로 남으면 같은 그룹을 쓰는 UserEventStreamBridgeIT의 메시지를 먼저 가져간다
@DirtiesContext
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB
class FallbackListenerPropagationIT {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean StringRedisTemplate redisTemplate; // XADD 실패 주입용

    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM event_publication WHERE serialized_event LIKE ?", "%" + userId + "%");
        jdbcTemplate.update("DELETE FROM user_notify_profile WHERE user_id = ?", userId);
    }

    @Test
    void 트랜잭션_밖_사이클_종료_알림_실패가_발행자로_전파되지_않는다() {
        // 텔레그램 미연결 프로필 — 알림은 FCM 위임(Redis XADD)만 탄다
        jdbcTemplate.update("INSERT INTO user_notify_profile (user_id, notification_prefs, balance_check_enabled, is_active, updated_at) "
                + "VALUES (?, '{}', true, true, now())", userId);
        doThrow(new RedisConnectionFailureException("redis down")).when(redisTemplate).opsForStream();

        assertThatCode(() -> eventPublisher.publishEvent(new CycleCompletedEvent(userId, UUID.randomUUID(), "IT",
                StrategyType.INFINITE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE)))
                .doesNotThrowAnyException();
    }
}
