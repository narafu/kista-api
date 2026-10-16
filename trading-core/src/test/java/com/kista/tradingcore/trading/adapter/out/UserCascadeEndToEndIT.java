package com.kista.tradingcore.trading.adapter.out;

import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.kernel.TimeZones;
import com.kista.shared.kernel.UserDeletedEvent;
import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import com.kista.shared.platform.crypto.AesCryptoService;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

// 탈퇴 cascade 실 컨텍스트·실 DB 검증 — 단위 테스트(mock)로는 못 잡는 세 가지를 잠근다.
// (1) AFTER_COMMIT 리스너의 쓰기가 실제로 커밋되는지(재발행 트랜잭션 합류 유실 회귀),
// (2) 정리 실패 시 trading.event_publication이 FAILED로 남고 UserEventResubmitScheduler가 재발행·첫 실패 알림하는지(completionAttempts 실측),
// (3) tombstone이 늦게 도착한 탈퇴 전 프로필 스냅샷을 막는지. 재발행은 UserEventRepublisher와 같은 "트랜잭션 안 publishEvent"로 재현한다
@Tag("integration")
@SpringBootTest(properties = "spring.data.redis.url=redis://localhost:6379")
// 실 Redis 스트림 구독자(user.* 컨슈머 그룹)가 살아 있는 컨텍스트 — 캐시로 남으면 같은 그룹을 쓰는 UserEventStreamBridgeIT의 메시지를 먼저 가져간다
@DirtiesContext
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB 커밋
class UserCascadeEndToEndIT {

    @Autowired ApplicationContext context;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AesCryptoService crypto;
    @Autowired ObjectMapper objectMapper;
    @MockitoSpyBean UserTradingDataEraser eraser;           // 정리 실패 주입용
    @MockitoBean TradingErrorReportPort errorReportPort;    // 관리자 텔레그램 대신 호출 검증

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID strategyId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 계좌(암호화 컬럼) → 전략 ACTIVE → 버전 → 사이클 → 오늘 PLANNED 주문(증권사 호출 없이 DB 삭제로 취소됨) → 활성 프로필 복제본
        jdbcTemplate.update("INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'KIS', ?, '01', ?, ?, now(), now())",
                accountId, userId, "탈퇴IT", crypto.encrypt("74420614-01"), crypto.encrypt("key"), crypto.encrypt("secret"));
        jdbcTemplate.update("INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, 'VR', 'SOXL', 'ACTIVE', 'NONE', now(), now())",
                strategyId, accountId);
        jdbcTemplate.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, 1, now())", versionId, strategyId);
        jdbcTemplate.update("INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) VALUES (?, ?, ?, ?, ?, now())",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), LocalDate.now(TimeZones.KST));
        jdbcTemplate.update("INSERT INTO orders (account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, order_leg, price, quantity, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SOXL', 'LOC', 'AT_CLOSE', 'BUY', 'UNKNOWN', 22.00, 5, 'PLANNED', now(), now())",
                accountId, cycleId, LocalDate.now(TimeZones.KST));
        jdbcTemplate.update("INSERT INTO user_notify_profile (user_id, notification_prefs, balance_check_enabled, is_active, updated_at, telegram_bot_token, chat_id) "
                + "VALUES (?, '{}', true, true, now() - interval '1 hour', 'cipher', 'chat')", userId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM orders WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM strategy_cycle WHERE id = ?", cycleId);
        jdbcTemplate.update("DELETE FROM strategy_version WHERE id = ?", versionId);
        jdbcTemplate.update("DELETE FROM strategy WHERE id = ?", strategyId);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM user_notify_profile WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM event_publication WHERE serialized_event LIKE ?", "%" + userId + "%");
    }

    // UserEventRepublisher.republishUserDeleted와 같은 경로 — 트랜잭션 안에서 발행하고 커밋 후 리스너가 돈다
    private void republishInTransaction(Object event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    @Test
    void 탈퇴_정리가_실제로_커밋되고_publication이_완료된다() {
        republishInTransaction(new UserDeletedEvent(userId));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders WHERE account_id = ?", Long.class, accountId)).isZero(); // PLANNED 삭제(취소 단계 커밋)
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM strategy WHERE id = ?", String.class, strategyId)).isEqualTo("PAUSED");
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM strategy WHERE id = ?", Boolean.class, strategyId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM strategy_cycle WHERE id = ?", Boolean.class, cycleId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id = ?", Boolean.class, accountId)).isTrue();
        Map<String, Object> profile = jdbcTemplate.queryForMap("SELECT is_active, telegram_bot_token, chat_id FROM user_notify_profile WHERE user_id = ?", userId);
        assertThat(profile.get("is_active")).isEqualTo(false);
        assertThat(profile.get("telegram_bot_token")).isNull();
        assertThat(profile.get("chat_id")).isNull();
        assertThat(cascadePublicationStatus()).isEqualTo("COMPLETED");

        // 탈퇴 전에 만들어진 프로필 스냅샷이 늦게 도착해도 tombstone을 되살리지 않는다
        republishInTransaction(new UserNotifyProfileChangedEvent(userId, Map.of(), true, true, "token", "chat",
                NotificationChannel.ALL, Instant.now().minusSeconds(60)));
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM user_notify_profile WHERE user_id = ?", Boolean.class, userId)).isFalse();
    }

    @Test
    void 정리_실패는_FAILED로_남고_재발행_잡이_첫_실패를_알린_뒤_복구한다() {
        doThrow(new IllegalStateException("주입된 정리 실패")).doCallRealMethod().when(eraser).erase(userId);

        republishInTransaction(new UserDeletedEvent(userId));

        // 롤백 — 계좌는 남고(전략은 취소 단계에서 이미 PAUSED) publication은 FAILED, 첫 시도 횟수 1
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NULL FROM accounts WHERE id = ?", Boolean.class, accountId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM strategy WHERE id = ?", String.class, strategyId)).isEqualTo("PAUSED");
        assertThat(cascadePublicationStatus()).isEqualTo("FAILED");
        assertThat(cascadePublicationAttempts()).isEqualTo(1);

        ReflectionTestUtils.invokeMethod(context.getBean("userEventResubmitScheduler"), "resubmitFailedUserEvents");

        verify(errorReportPort, atLeastOnce()).reportError(any());
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id = ?", Boolean.class, accountId)).isTrue();
        assertThat(cascadePublicationStatus()).isEqualTo("COMPLETED");
    }

    // 구버전 trading-core가 새 필드(occurredAt 등)가 실린 payload를 받아도 앱 ObjectMapper는 모르는 필드를 무시한다
    @Test
    void 앱_ObjectMapper는_모르는_필드를_무시한다() {
        String json = "{\"userId\":\"" + userId + "\",\"futureField\":1}";
        assertThat(objectMapper.readValue(json, UserDeletedEvent.class).userId()).isEqualTo(userId);
    }

    private String cascadePublicationStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM event_publication WHERE serialized_event LIKE ? AND listener_id LIKE ?",
                String.class, "%" + userId + "%", "%UserCascadeListener%");
    }

    private Integer cascadePublicationAttempts() {
        return jdbcTemplate.queryForObject("SELECT completion_attempts FROM event_publication WHERE serialized_event LIKE ? AND listener_id LIKE ?",
                Integer.class, "%" + userId + "%", "%UserCascadeListener%");
    }
}
