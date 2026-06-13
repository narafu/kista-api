package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.BrokerTokenCachePort;
import com.kista.domain.port.out.TossConnectionTestPort;
import com.kista.domain.port.out.TossTokenPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

// TossTokenPort + TossConnectionTestPort 통합 구현체
// OAuth form-encoded 호출 — tossRestTemplate 직접 사용 (TossHttpClient 순환 의존 회피)
@Slf4j
@Component
@RequiredArgsConstructor
public class TossAuthApi implements TossTokenPort, TossConnectionTestPort {

    // 계좌별 락 — 동시 요청 시 중복 발급 방지
    private final ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    private final RestTemplate tossRestTemplate;
    private final BrokerTokenCachePort brokerTokenCachePort;
    @Value("${toss.base-url}")
    private final String tossBaseUrl;

    // ── TossTokenPort ──────────────────────────────────────────────────────────

    @Override
    public String getToken(UUID accountId, String clientId, String clientSecret) {
        // 1차 조회 — 락 없이 빠른 경로
        Optional<String> cached = brokerTokenCachePort.findValidToken(accountId, threshold());
        if (cached.isPresent()) {
            return cached.get();
        }
        // 캐시 miss 시 accountId별 락으로 경합 차단
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 2차 조회 (double-check) — 다른 스레드가 이미 발급했을 수 있음
            return brokerTokenCachePort.findValidToken(accountId, threshold())
                    .orElseGet(() -> fetchAndCacheToken(accountId, clientId, clientSecret));
        } finally {
            lock.unlock();
        }
    }

    private String fetchAndCacheToken(UUID accountId, String clientId, String clientSecret) {
        log.info("Toss 토큰 신규 발급: accountId={}", accountId);
        TokenResponse response = issueOAuthToken(clientId, clientSecret);
        // 만료 5분 전 갱신 트리거를 위해 expiresIn에서 300초 차감
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(response.expiresIn() - 300);
        brokerTokenCachePort.saveToken(accountId, response.accessToken(), expiresAt);
        return response.accessToken();
    }

    // 만료 5분 전부터 무효 처리 — 경계값 만료 오류 방지
    private OffsetDateTime threshold() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
    }

    // ── TossConnectionTestPort ─────────────────────────────────────────────────

    @Override
    public String testAndFetchAccountSeq(String clientId, String clientSecret) {
        // 토큰 발급 — accountId 미보유이므로 캐시 저장 없음
        String token = issueOAuthToken(clientId, clientSecret).accessToken();
        return fetchAccountSeq(token);
    }

    // ── private helpers ────────────────────────────────────────────────────────

    // Toss OAuth form-encoded 토큰 발급 (grant_type=client_credentials)
    private TokenResponse issueOAuthToken(String clientId, String clientSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        try {
            ResponseEntity<TokenResponse> response = tossRestTemplate.exchange(
                    tossBaseUrl + "/oauth2/token",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    TokenResponse.class);
            if (response.getBody() == null || response.getBody().accessToken() == null) {
                throw new Account.InvalidKisKeyException();
            }
            return response.getBody();
        } catch (RestClientException e) {
            log.warn("Toss OAuth 토큰 발급 실패: {}", e.getMessage());
            throw new Account.InvalidKisKeyException();
        }
    }

    // GET /api/v1/accounts → 첫 번째 accountSeq 반환
    private String fetchAccountSeq(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        try {
            ResponseEntity<AccountsResponse> response = tossRestTemplate.exchange(
                    tossBaseUrl + "/api/v1/accounts",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    AccountsResponse.class);
            List<AccountItem> accounts = response.getBody() == null ? null : response.getBody().result();
            if (accounts == null || accounts.isEmpty()) {
                log.warn("Toss 계좌 목록 비어있음 — clientId 확인 필요");
                throw new Account.InvalidKisKeyException();
            }
            return String.valueOf(accounts.get(0).accountSeq());
        } catch (RestClientException e) {
            log.warn("Toss 계좌 조회 실패: {}", e.getMessage(), e);
            throw new Account.InvalidKisKeyException();
        }
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    // package-private — TossAuthApiTest에서 직접 생성하여 stub에 사용
    record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn    // 토큰 유효 초 (기본 86400)
    ) {}

    // GET /api/v1/accounts 응답 래퍼 — {"result":[...]}
    record AccountsResponse(
        @JsonProperty("result") List<AccountItem> result
    ) {}

    // package-private — TossAuthApiTest에서 직접 생성하여 stub에 사용
    record AccountItem(
        @JsonProperty("accountSeq") int accountSeq,   // 계좌 일련번호 — kisAccountType에 저장
        @JsonProperty("accountNo") String accountNo   // 계좌번호 (마스킹 가능)
    ) {}
}
