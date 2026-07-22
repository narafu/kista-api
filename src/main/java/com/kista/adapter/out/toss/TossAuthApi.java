package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.broker.BrokerConnectionTestPort;
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

import java.util.List;
import java.util.UUID;

// BrokerConnectionTestPort 구현체 — getToken/getAdminToken/recover* 는 TossHttpClient에 직접 주입되는 구체 메서드
// OAuth form-encoded 호출 — tossRestTemplate 직접 사용 (TossHttpClient 순환 의존 회피)
@Slf4j
@Component
@RequiredArgsConstructor
class TossAuthApi implements BrokerConnectionTestPort {

    private final RestTemplate tossRestTemplate;
    private final TossDistributedTokenCoordinator tokenCoordinator;
    @Value("${toss.base-url}")
    private final String tossBaseUrl;
    @Value("${toss.admin-client-id}")
    private final String adminClientId;         // 공통 API용 관리자 Toss client_id
    @Value("${toss.admin-client-secret}")
    private final String adminClientSecret;     // 공통 API용 관리자 Toss client_secret

    // ── 토큰 발급 / 401 복구 — TossHttpClient가 구체 타입으로 직접 주입 ─────────────

    public String getToken(UUID accountId, String clientId, String clientSecret) {
        return tokenCoordinator.getAccountToken(
                accountId,
                () -> issueAccountToken(accountId, clientId, clientSecret));
    }

    private TossTokenStore.TokenValue issueAccountToken(
            UUID accountId, String clientId, String clientSecret) {
        log.info("Toss 토큰 신규 발급: accountId={}", accountId);
        TokenResponse response = issueOAuthToken(clientId, clientSecret);
        return new TossTokenStore.TokenValue(response.accessToken(), response.expiresIn());
    }

    public TossDistributedTokenCoordinator.RecoveredToken recoverToken(
            UUID accountId, String clientId, String clientSecret, String rejectedAccessToken) {
        return tokenCoordinator.recoverAccountToken(
                accountId,
                rejectedAccessToken,
                () -> issueAccountToken(accountId, clientId, clientSecret));
    }

    // ── 관리자(공통 API) 토큰 — 시세·환율·시장정보 공통 API 전용 ─────────────────

    public String getAdminToken() {
        return tokenCoordinator.getAdminToken(this::issueAdminToken);
    }

    public TossDistributedTokenCoordinator.RecoveredToken recoverAdminToken(String rejectedAccessToken) {
        return tokenCoordinator.recoverAdminToken(rejectedAccessToken, this::issueAdminToken);
    }

    // ── BrokerConnectionTestPort ───────────────────────────────────────────────

    @Override
    public Account.Broker supports() {
        return Account.Broker.TOSS;
    }

    @Override
    public String verifyAccount(String appKey, String secretKey, String accountNo) {
        // Toss는 계좌번호(accountNo) 대신 clientId/secret으로 accountSeq를 조회해 검증
        String token = issueOAuthToken(appKey, secretKey).accessToken();
        return fetchAccountSeq(token);
    }

    @Override
    public void verifyCredentials(String appKey, String secretKey, UUID accountId) {
        // Toss는 자격증명 단독 검증 엔드포인트가 없어 accounts 조회로 검증 (accountId 미사용 — 캐시 저장 없음)
        String token = issueOAuthToken(appKey, secretKey).accessToken();
        fetchAccountSeq(token);
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private TossTokenStore.TokenValue issueAdminToken() {
        TokenResponse response = issueOAuthToken(adminClientId, adminClientSecret);
        return new TossTokenStore.TokenValue(
                response.accessToken(), response.expiresIn());
    }

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
                throw new Account.InvalidBrokerKeyException();
            }
            return response.getBody();
        } catch (RestClientException e) {
            log.warn("Toss OAuth 토큰 발급 실패: {}", e.getMessage());
            throw new Account.InvalidBrokerKeyException();
        }
    }

    // GET /api/v1/accounts → 첫 번째 accountSeq 반환
    private String fetchAccountSeq(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        try {
            ResponseEntity<TossResult<List<AccountItem>>> response = tossRestTemplate.exchange(
                    tossBaseUrl + "/api/v1/accounts",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<TossResult<List<AccountItem>>>() {});
            List<AccountItem> accounts = response.getBody() == null ? null : response.getBody().result();
            if (accounts == null || accounts.isEmpty()) {
                log.warn("Toss 계좌 목록 비어있음 — clientId 확인 필요");
                throw new Account.InvalidBrokerKeyException();
            }
            return String.valueOf(accounts.get(0).accountSeq());
        } catch (RestClientException e) {
            log.warn("Toss 계좌 조회 실패: {}", e.getMessage(), e);
            throw new Account.InvalidBrokerKeyException();
        }
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    // package-private — TossAuthApiTest에서 직접 생성하여 stub에 사용
    record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn    // 토큰 유효 초 (기본 86400)
    ) {}

    // package-private — TossAuthApiTest에서 직접 생성하여 stub에 사용
    record AccountItem(
        @JsonProperty("accountSeq") int accountSeq,   // 계좌 일련번호 — brokerAccountCode에 저장
        @JsonProperty("accountNo") String accountNo   // 계좌번호 (마스킹 가능)
    ) {}
}
