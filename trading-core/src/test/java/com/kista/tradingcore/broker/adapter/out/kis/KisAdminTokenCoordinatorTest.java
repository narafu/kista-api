package com.kista.tradingcore.broker.adapter.out.kis;

import com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisAdminTokenCoordinator — Redis 캐시")
class KisAdminTokenCoordinatorTest {

    static final String APP_KEY = "admin-app-key";
    // 키 = 접두사 + sha256(appKey) 앞 8자
    static final String KEY = "kis:admin:token:" + com.kista.shared.platform.crypto.Sha256.hex(APP_KEY).substring(0, 8);

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;
    KisAdminTokenCoordinator coordinator;
    AtomicInteger issued = new AtomicInteger();
    TokenCoordinator.TokenIssuer issuer = () -> {
        issued.incrementAndGet();
        return new TokenCoordinator.IssuedToken("new-token", 86400);
    };

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        coordinator = new KisAdminTokenCoordinator(redis);
    }

    @Test
    @DisplayName("캐시 히트면 issuer를 호출하지 않는다")
    void cacheHit() {
        when(ops.get(KEY)).thenReturn("cached");

        assertThat(coordinator.obtain(APP_KEY, issuer)).isEqualTo("cached");
        assertThat(issued.get()).isZero();
    }

    @Test
    @DisplayName("캐시 미스면 발급 후 만료 60초 전 TTL로 저장한다")
    void cacheMissIssuesAndStores() {
        when(ops.get(KEY)).thenReturn(null);

        assertThat(coordinator.obtain(APP_KEY, issuer)).isEqualTo("new-token");

        verify(ops).set(KEY, "new-token", Duration.ofSeconds(86340));
        assertThat(issued.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("recover: 저장값이 거절된 토큰과 같을 때만 삭제 후 재발급")
    void recoverDeletesOnlyWhenSame() {
        when(ops.get(KEY)).thenReturn("rejected", (String) null);

        var result = coordinator.recover(APP_KEY, "rejected", issuer);

        verify(redis).delete(KEY);
        assertThat(result.accessToken()).isEqualTo("new-token");
        assertThat(result.freshlyIssued()).isTrue();
    }

    @Test
    @DisplayName("recover: 이미 다른 토큰으로 교체됐으면 삭제하지 않고 그 토큰을 쓴다")
    void recoverKeepsNewerToken() {
        when(ops.get(KEY)).thenReturn("newer");

        var result = coordinator.recover(APP_KEY, "rejected", issuer);

        verify(redis, never()).delete(KEY);
        assertThat(result.accessToken()).isEqualTo("newer");
        assertThat(issued.get()).isZero();
    }

    @Test
    @DisplayName("Redis 예외여도 발급한 토큰은 반환한다")
    void redisFailureStillReturnsToken() {
        when(ops.get(KEY)).thenThrow(new RuntimeException("redis down"));
        doThrow(new RuntimeException("redis down")).when(ops).set(any(), any(), any(Duration.class));

        assertThat(coordinator.obtain(APP_KEY, issuer)).isEqualTo("new-token");
    }

    @Test
    @DisplayName("앱키가 다르면 Redis 키가 달라 옛 앱키 토큰을 재사용하지 않는다")
    void differentAppKeyUsesDifferentRedisKey() {
        when(ops.get(KEY)).thenReturn("cached");

        assertThat(KEY).startsWith("kis:admin:token:").hasSize("kis:admin:token:".length() + 8);
        assertThat(coordinator.obtain(APP_KEY, issuer)).isEqualTo("cached");

        assertThat(coordinator.obtain("rotated-app-key", issuer)).isEqualTo("new-token"); // 다른 키는 stub 없음 → null → 발급
        assertThat(issued.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Redis 조회가 실패해도 메모리에 유효한 토큰이 있으면 재발급하지 않는다")
    void redisFailureFallsBackToMemory() {
        when(ops.get(KEY)).thenReturn(null).thenThrow(new RuntimeException("redis down"));

        assertThat(coordinator.obtain(APP_KEY, issuer)).isEqualTo("new-token"); // 최초 발급 → 메모리 보관
        assertThat(coordinator.obtain(APP_KEY, issuer)).isEqualTo("new-token"); // Redis 장애 → 메모리 사용
        assertThat(issued.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("recover: Redis 장애 중 거절된 토큰이 메모리 값이면 메모리를 비우고 재발급한다")
    void recoverClearsRejectedMemoryToken() {
        when(ops.get(KEY)).thenReturn(null).thenReturn(null).thenThrow(new RuntimeException("redis down"));
        coordinator.obtain(APP_KEY, issuer); // 메모리에 new-token (get 2회: 사전·double-check)

        var result = coordinator.recover(APP_KEY, "new-token", issuer);

        assertThat(result.accessToken()).isEqualTo("new-token");
        assertThat(issued.get()).isEqualTo(2);
    }
}
