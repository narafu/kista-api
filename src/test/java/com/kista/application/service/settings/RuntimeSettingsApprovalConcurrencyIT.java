package com.kista.application.service.settings;

import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminSettingsUseCase;
import com.kista.domain.port.in.UserUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class RuntimeSettingsApprovalConcurrencyIT {

    private static final long SIGNUP_GATE = 918_273L; // 테스트 전용 PostgreSQL advisory lock 키

    @Autowired AdminSettingsUseCase adminSettingsUseCase; // 실제 설정 변경 트랜잭션
    @Autowired UserUseCase userUseCase; // 실제 가입 트랜잭션
    @Autowired JdbcTemplate jdbcTemplate; // 경합 제어와 최종 상태 검증
    @Autowired PlatformTransactionManager transactionManager; // advisory lock 보유 트랜잭션

    private final ExecutorService executor = Executors.newFixedThreadPool(3); // 경합 참여 스레드
    private UUID adminId; // 감사 로그 FK용 관리자
    private UUID signupId; // 경합 가입 사용자

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        signupId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, kakao_id, nickname, status, role, notification_channel, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'ACTIVE', 'ADMIN', 'TELEGRAM', now(), now())",
                adminId, "admin-" + adminId, "admin");
        adminSettingsUseCase.updateSettings(adminId, RuntimeSettings.defaults(), true);

        // 가입 INSERT를 승인 설정 판정 뒤에 일시 정지시키는 테스트 전용 트리거다.
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION block_runtime_settings_signup() RETURNS trigger AS $$ " +
                "BEGIN PERFORM pg_advisory_xact_lock(" + SIGNUP_GATE + "); RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbcTemplate.execute("CREATE TRIGGER block_runtime_settings_signup_trigger BEFORE INSERT ON users " +
                "FOR EACH ROW EXECUTE FUNCTION block_runtime_settings_signup()");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS block_runtime_settings_signup_trigger ON users");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS block_runtime_settings_signup()");
        adminSettingsUseCase.updateSettings(adminId, RuntimeSettings.defaults(), true);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", adminId, signupId);
        executor.shutdownNow();
    }

    @Test
    void disablingApprovalWhileSignupIsPausedLeavesNoPendingUser() throws Exception {
        CountDownLatch gateHeld = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        Future<?> gate = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, SIGNUP_GATE);
            gateHeld.countDown();
            await(releaseGate);
        }));
        assertThat(gateHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        Future<User> signup = executor.submit(() ->
                userUseCase.register("signup-" + signupId, "signup", signupId));
        awaitWaitingAdvisoryLock();

        RuntimeSettings defaults = RuntimeSettings.defaults();
        RuntimeSettings approvalDisabled = new RuntimeSettings(false, defaults.brokers(), defaults.strategies());
        Future<RuntimeSettings> disable = executor.submit(() ->
                adminSettingsUseCase.updateSettings(adminId, approvalDisabled, true));

        releaseGate.countDown();
        gate.get();
        signup.get();
        disable.get();

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ? AND status = 'PENDING'", Integer.class, signupId);
        assertThat(pending).isZero();
    }

    private void awaitWaitingAdvisoryLock() throws InterruptedException {
        // 트리거에서 signup INSERT가 실제로 멈춘 뒤 관리자 변경을 시작한다.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted = false",
                    Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("signup did not reach the advisory lock");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrency gate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for concurrency gate", e);
        }
    }
}
