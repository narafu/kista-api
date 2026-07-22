package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Component
class TossDistributedTokenCoordinator {

    private static final String ADMIN_SCOPE = "admin"; // 모든 계좌·인스턴스가 공유하는 admin 토큰 발급 lease scope
    // TossConfig의 OAuth RestTemplate 타임아웃(connect 3s + response 10s ≈ 13s 최악 케이스)보다
    // 여유 있게, 그러나 owner crash 시 lease가 회수 불가 상태로 남는 blast radius를 최소화하도록 짧게 설정
    private static final Duration LEASE_TTL = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50); // lease/canonical polling 간격
    private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofMinutes(5); // canonical TTL을 OAuth 만료보다 앞당기는 여유분
    // POLL_INTERVAL(50ms) * 500 = 25초 — LEASE_TTL(20초)을 5초 여유로 초과해,
    // follower가 정상 owner의 완료 또는 lease 자연 만료보다 먼저 포기하지 않도록 보장
    private static final int DEFAULT_MAX_POLL_ATTEMPTS = 500;

    private final TossTokenStore tokenStore; // Redis canonical token/lease 저장소
    private final PollWaiter pollWaiter; // polling 간 대기 전략 — 실서비스는 Thread.sleep, 테스트는 즉시 반환
    private final Supplier<String> ownerIds; // lease owner id 생성기 — 실서비스는 UUID, 테스트는 고정 문자열
    private final int maxPollAttempts; // 인스턴스 단위 poll 횟수 상한 — 기본값은 DEFAULT_MAX_POLL_ATTEMPTS

    @Autowired
    TossDistributedTokenCoordinator(TossTokenStore tokenStore) {
        this(tokenStore, TossDistributedTokenCoordinator::sleep,
                () -> UUID.randomUUID().toString(), DEFAULT_MAX_POLL_ATTEMPTS);
    }

    TossDistributedTokenCoordinator(
            TossTokenStore tokenStore,
            PollWaiter pollWaiter,
            Supplier<String> ownerIds,
            int maxPollAttempts
    ) {
        this.tokenStore = tokenStore;
        this.pollWaiter = pollWaiter;
        this.ownerIds = ownerIds;
        this.maxPollAttempts = maxPollAttempts;
    }

    String getAccountToken(UUID accountId, TokenIssuer issuer) {
        // 최초 조회는 복구 흐름이 아니므로 freshlyIssued 여부를 폐기하고 토큰 문자열만 반환
        return getOrIssue(accountScope(accountId), null, issuer).accessToken();
    }

    RecoveredToken recoverAccountToken(UUID accountId, String rejectedToken, TokenIssuer issuer) {
        return getOrIssue(accountScope(accountId), rejectedToken, issuer);
    }

    String getAdminToken(TokenIssuer issuer) {
        return getOrIssue(ADMIN_SCOPE, null, issuer).accessToken();
    }

    RecoveredToken recoverAdminToken(String rejectedToken, TokenIssuer issuer) {
        return getOrIssue(ADMIN_SCOPE, rejectedToken, issuer);
    }

    private RecoveredToken getOrIssue(String scope, String rejectedToken, TokenIssuer issuer) {
        Optional<TossTokenStore.CanonicalToken> current = tokenStore.find(scope);
        RecoveredToken reusable = reusableToken(scope, current, rejectedToken);
        if (reusable != null) {
            return reusable;
        }

        // lease 획득을 시도하고, 실패하면 다른 owner의 canonical write를 기다리며 재조회 — 성공 시 자신이 owner가 되어 발급
        for (int attempt = 0; attempt <= maxPollAttempts; attempt++) {
            Optional<TossTokenStore.Lease> lease = tokenStore.tryAcquire(
                    scope, ownerIds.get(), LEASE_TTL);
            if (lease.isPresent()) {
                return issueAsOwner(lease.orElseThrow(), rejectedToken, issuer);
            }
            if (attempt == maxPollAttempts) {
                break;
            }
            pollWaiter.await(POLL_INTERVAL);
            current = tokenStore.find(scope);
            reusable = reusableToken(scope, current, rejectedToken);
            if (reusable != null) {
                return reusable;
            }
        }
        throw new TossApiException("Toss token Redis lease 대기 시간 초과: " + scope, null);
    }

    private RecoveredToken issueAsOwner(
            TossTokenStore.Lease lease,
            String rejectedToken,
            TokenIssuer issuer
    ) {
        try {
            Optional<TossTokenStore.CanonicalToken> current = tokenStore.find(lease.scope());
            RecoveredToken reusable = reusableToken(lease.scope(), current, rejectedToken);
            if (reusable != null) {
                return reusable;
            }

            TossTokenStore.TokenValue issued = issuer.issue();
            Duration canonicalTtl = Duration.ofSeconds(issued.expiresInSeconds())
                    .minus(TOKEN_EXPIRY_MARGIN);
            if (canonicalTtl.isZero() || canonicalTtl.isNegative()) {
                throw new TossApiException("Toss token 만료 시간이 5분 이하입니다", null);
            }
            TossTokenStore.StoreResult result = tokenStore.storeIfCurrent(
                    lease, issued, canonicalTtl);
            if (result == TossTokenStore.StoreResult.STORED) {
                // 방금 OAuth로 새로 발급한 토큰 — 리소스 서버 전파 지연 가능성이 있어 대기 필요
                return new RecoveredToken(issued.accessToken(), true);
            }
            return awaitNewerCanonical(lease.scope(), lease.generation());
        } finally {
            tokenStore.release(lease);
        }
    }

    private RecoveredToken awaitNewerCanonical(String scope, long staleGeneration) {
        // 자신의 canonical write가 stale로 거부된 경우 — 더 큰 fencing 세대의 canonical이 나타날 때까지 대기
        for (int attempt = 0; attempt <= maxPollAttempts; attempt++) {
            Optional<TossTokenStore.CanonicalToken> current = tokenStore.find(scope);
            if (current.isPresent() && current.orElseThrow().generation() > staleGeneration) {
                // 다른 인스턴스가 방금 발급해 저장한 신규 세대 — 마찬가지로 전파 지연 가능성이 있어 대기 필요
                return new RecoveredToken(current.orElseThrow().accessToken(), true);
            }
            if (attempt == maxPollAttempts) {
                break;
            }
            pollWaiter.await(POLL_INTERVAL);
        }
        throw new TossApiException(
                "Toss token newer fencing generation 대기 시간 초과: " + scope, null);
    }

    private RecoveredToken reusableToken(
            String scope,
            Optional<TossTokenStore.CanonicalToken> current,
            String rejectedToken
    ) {
        if (rejectedToken == null) {
            // 복구 흐름이 아닌 최초 조회 — freshlyIssued는 호출부에서 사용하지 않으므로 false 고정
            return current.map(token -> new RecoveredToken(token.accessToken(), false)).orElse(null);
        }
        if (current.isPresent()
                && !Objects.equals(current.orElseThrow().accessToken(), rejectedToken)) {
            // 이미 다른 인스턴스가 발급·저장해 둔 canonical 토큰 — Redis 읽기만으로 재사용, 전파 대기 불필요
            return new RecoveredToken(current.orElseThrow().accessToken(), false);
        }
        // 거절된 토큰과 동일 — 최근 발급 지문 보호 구간 내 재사용은 여전히 전파 지연 위험이 있어 대기 필요
        boolean recentFingerprint = tokenStore.matchesRecentFingerprint(scope, rejectedToken);
        if (recentFingerprint) {
            // 정상 전파 지연 케이스에서도 흔히 발생하는 경로라 WARN 대신 DEBUG로 남긴다 —
            // 같은 scope에서 반복 관측되면(최종적으로 재시도 소진 → 503) 원인 추적용 단서로 사용
            log.debug("Toss 토큰 최근 발급 지문 보호 구간 내 재사용: scope={}", scope);
        }
        return recentFingerprint
                ? new RecoveredToken(rejectedToken, true)
                : null;
    }

    static String accountScope(UUID accountId) {
        return "account:" + accountId;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TossApiException("Toss token Redis lease 대기 중 interrupted", exception);
        }
    }

    @FunctionalInterface
    interface PollWaiter {
        void await(Duration duration);
    }

    @FunctionalInterface
    interface TokenIssuer {
        TossTokenStore.TokenValue issue();
    }

    // 401 복구 결과 — freshlyIssued=false는 이미 다른 인스턴스가 저장해 둔 canonical 토큰을
    // Redis 읽기만으로 재사용한 경우(전파 지연 위험 없음), true는 방금 발급되었거나
    // 최근 발급 지문 보호 구간 내라 전파 지연 위험이 남아 있는 경우(호출부가 백오프 대기 필요)
    record RecoveredToken(String accessToken, boolean freshlyIssued) {}
}
