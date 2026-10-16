package com.kista.api.web;

import com.kista.api.notify.application.port.output.NotifyPort;
import com.kista.api.user.application.usecase.GetUserSettingsQuery;
import com.kista.api.user.application.usecase.UserSettingsUseCase;
import com.kista.shared.kernel.UserDeletedEvent;
import com.kista.shared.platform.redis.RedisStreamConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

// root 발행 측 실 컨텍스트·실 DB 검증 — XADD가 실패하면 public.event_publication이 FAILED로 남고(예전엔 예외를 삼켜 완료로 기록돼 영구 유실),
// UserEventResubmitScheduler가 재발행해 Redis Stream에 실제로 들어가며 첫 실패를 관리자에게 알리는지, 트랜잭션 밖이던 발행 지점이 500을 내지 않는지 잠근다
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB·Redis
class UserEventResubmitEndToEndIT {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserEventResubmitScheduler scheduler;
    @MockitoSpyBean StringRedisTemplate redisTemplate; // XADD 실패 주입용
    @MockitoBean NotifyPort notifyPort;                // 관리자 텔레그램 대신 호출 검증
    @Autowired UserSettingsUseCase userSettingsUseCase;
    @Autowired GetUserSettingsQuery getUserSettingsQuery;

    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM public.event_publication WHERE serialized_event LIKE ?", "%" + userId + "%");
        jdbcTemplate.update("DELETE FROM user_settings WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        // 재발행으로 들어간 테스트 메시지 제거 — 로컬 trading-core가 소비해도 없는 사용자라 무해하지만 남기지 않는다
        redisTemplate.<String, String>opsForStream().range(RedisStreamConfig.USER_DELETED_STREAM, Range.unbounded()).stream()
                .filter(r -> r.getValue().get("payload").contains(userId.toString()))
                .forEach(r -> redisTemplate.opsForStream().delete(RedisStreamConfig.USER_DELETED_STREAM, r.getId()));
    }

    @Test
    void XADD_실패는_FAILED로_남고_재발행_잡이_알린_뒤_스트림에_넣는다() {
        doThrow(new RedisConnectionFailureException("redis down")).doCallRealMethod().when(redisTemplate).opsForStream();

        // UserCascadeDeleter와 같은 경로 — 트랜잭션 안에서 발행, 커밋 후 리스너가 XADD
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserDeletedEvent(userId)));

        assertThat(publisherPublicationStatus()).isEqualTo("FAILED");
        doCallRealMethod().when(redisTemplate).opsForStream();

        scheduler.resubmitFailedUserEvents();

        verify(notifyPort).notifyError(argThat(e -> e.getMessage().contains("UserDeletedEvent")));
        assertThat(publisherPublicationStatus()).isEqualTo("COMPLETED");
        assertThat(redisTemplate.<String, String>opsForStream().range(RedisStreamConfig.USER_DELETED_STREAM, Range.unbounded()))
                .anyMatch(r -> r.getValue().get("payload").contains(userId.toString()));
    }

    // 트랜잭션 밖이던 잔고검증 토글 — Redis 장애여도 설정 변경은 성공하고(500 아님) 복제본 이벤트는 FAILED로 남아 재발행 대상이 된다
    @Test
    void 잔고검증_토글은_Redis_장애에도_성공하고_이벤트는_FAILED로_남는다() {
        jdbcTemplate.update("INSERT INTO users (id, kakao_id, status, role, notification_channel, created_at, updated_at) VALUES (?, ?, 'ACTIVE', 'USER', 'TELEGRAM', now(), now())",
                userId, "kakao_" + userId);
        doThrow(new RedisConnectionFailureException("redis down")).doCallRealMethod().when(redisTemplate).opsForStream();

        userSettingsUseCase.updateBalanceCheck(userId, false);

        assertThat(getUserSettingsQuery.getByUserId(userId).balanceCheckEnabled()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM public.event_publication WHERE serialized_event LIKE ? AND listener_id LIKE ?",
                String.class, "%" + userId + "%", "%UserEventStreamPublisher%")).isEqualTo("FAILED");
    }

    private String publisherPublicationStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM public.event_publication WHERE serialized_event LIKE ? AND listener_id LIKE ?",
                String.class, "%" + userId + "%", "%UserEventStreamPublisher%");
    }
}
