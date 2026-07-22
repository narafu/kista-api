package com.kista.adapter.out.kis;

import com.kista.adapter.out.broker.TokenCoordinator;
import com.kista.domain.port.out.BrokerTokenCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisTokenCoordinator — obtain/recover")
class KisTokenCoordinatorTest {

    @Mock BrokerTokenCachePort cachePort;

    KisTokenCoordinator coordinator;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        coordinator = new KisTokenCoordinator(cachePort);
    }

    @Test
    @DisplayName("캐시에 유효 토큰 있으면 issuer 호출 없이 반환")
    void obtain_whenCacheHit_returnsCachedTokenWithoutIssuing() {
        when(cachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.of("cached-token"));
        AtomicInteger issueCount = new AtomicInteger();

        String result = coordinator.obtain(ACCOUNT_ID, issuedTokenCounter(issueCount));

        assertThat(result).isEqualTo("cached-token");
        assertThat(issueCount.get()).isZero();
    }

    @Test
    @DisplayName("캐시 미스 시 issuer로 신규 발급 후 saveToken으로 캐시 저장")
    void obtain_whenCacheMiss_issuesAndSavesToken() {
        when(cachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.empty());

        String result = coordinator.obtain(ACCOUNT_ID, () ->
                new TokenCoordinator.IssuedToken("new-token", 3600));

        assertThat(result).isEqualTo("new-token");
        ArgumentCaptor<OffsetDateTime> expiresCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(cachePort).saveToken(eq(ACCOUNT_ID), eq("new-token"), expiresCaptor.capture());
        // KST(+09:00) 기준으로 now+expiresInSeconds가 저장돼야 함
        assertThat(expiresCaptor.getValue().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        assertThat(expiresCaptor.getValue()).isAfter(OffsetDateTime.now().plusMinutes(59));
    }

    @Test
    @DisplayName("캐시 miss 후 lock 내 2차 조회(double-check)에서 hit 시 issuer 미호출")
    void obtain_doubleCheck_preventsRedundantIssue() {
        when(cachePort.findValidToken(eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("concurrent-token"));
        AtomicInteger issueCount = new AtomicInteger();

        String result = coordinator.obtain(ACCOUNT_ID, issuedTokenCounter(issueCount));

        assertThat(result).isEqualTo("concurrent-token");
        assertThat(issueCount.get()).isZero();
    }

    @Test
    @DisplayName("recover는 accountId+rejectedToken으로 무효화 후 재발급해 freshlyIssued=true를 반환한다")
    void recover_invalidatesThenReissues_alwaysFreshlyIssued() {
        // 무효화 이후 캐시는 miss 상태 — obtain이 신규 발급 경로로 재진입
        when(cachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.empty());

        TokenCoordinator.RecoveredToken result = coordinator.recover(ACCOUNT_ID, "rejected-token", () ->
                new TokenCoordinator.IssuedToken("fresh-token", 3600));

        assertThat(result.accessToken()).isEqualTo("fresh-token");
        assertThat(result.freshlyIssued()).isTrue();
        verify(cachePort).invalidateToken(eq(ACCOUNT_ID), eq("rejected-token"), any());
        verify(cachePort).saveToken(eq(ACCOUNT_ID), eq("fresh-token"), any());
    }

    private TokenCoordinator.TokenIssuer issuedTokenCounter(AtomicInteger issueCount) {
        return () -> {
            issueCount.incrementAndGet();
            return new TokenCoordinator.IssuedToken("issued-token", 3600);
        };
    }
}
