package com.kista.api.web;

import com.kista.api.notify.application.port.output.NotifyPort;
import com.kista.api.user.application.event.UserSeedPromotedEvent;
import com.kista.shared.kernel.UserDeletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// AFTER_COMMIT 리스너 안의 쓰기가 실제로 DB에 반영되는지 실 컨텍스트·실 DB로 잠근다 —
// 리스너가 경계를 열지 않으면 REQUIRED 쓰기가 이미 커밋된 발행 트랜잭션에 합류해 조용히 유실된다
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB
class AfterCommitWriteEndToEndIT {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean NotifyPort notifyPort; // UserDeletedNotifier의 관리자 텔레그램 차단

    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM public.event_publication WHERE serialized_event LIKE ?", "%" + userId + "%");
        jdbcTemplate.update("DELETE FROM audit_logs WHERE target_id = ?", userId);
        jdbcTemplate.update("DELETE FROM fcm_device_tokens WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    // AdminSeedPromoter와 같은 경로 — 트랜잭션 안에서 발행, 커밋 후 UserSeedPromotedAuditListener가 감사 로그 저장
    @Test
    void seed_승격_감사_로그가_저장된다() {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserSeedPromotedEvent(userId)));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    // UserCascadeDeleter와 같은 경로 — 커밋 후 UserFcmCleanupListener가 FCM 토큰 삭제
    @Test
    void 탈퇴_시_FCM_토큰이_삭제된다() {
        jdbcTemplate.update("INSERT INTO users (id, kakao_id, status, role, notification_channel, created_at, updated_at) VALUES (?, ?, 'ACTIVE', 'USER', 'FCM', now(), now())",
                userId, "kakao_" + userId);
        jdbcTemplate.update("INSERT INTO fcm_device_tokens (user_id, token, platform) VALUES (?, ?, 'ANDROID')", userId, "tok_" + userId);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserDeletedEvent(userId)));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fcm_device_tokens WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

}
