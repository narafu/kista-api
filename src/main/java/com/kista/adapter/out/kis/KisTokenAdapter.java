package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.port.out.KisTokenCachePort;
import com.kista.domain.port.out.KisTokenPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenAdapter implements KisTokenPort {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KIS_EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 계좌별 락 — 동시 요청 시 중복 발급 방지
    private final ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    // KisHttpClient를 주입받지 않음 — KisHttpClient가 KisTokenPort에 의존하므로 순환 방지
    private final RestTemplate kisRestTemplate;
    private final KisTokenCachePort kisTokenCachePort;
    @Value("${kis.base-url}")
    private final String kisBaseUrl;

    @Override
    public String getToken(UUID accountId, String appKey, String appSecret) {
        // 1차 조회 — 락 없이 빠른 경로
        Optional<String> cached = kisTokenCachePort.findValidToken(accountId, threshold());
        if (cached.isPresent()) {
            return cached.get();
        }
        // 캐시 miss 시 accountId별 락으로 경합 차단 — 같은 계좌 동시 호출이 N번 발급하는 것 방지
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 2차 조회 (double-check) — 다른 스레드가 이미 발급했을 수 있음
            return kisTokenCachePort.findValidToken(accountId, threshold())
                    .orElseGet(() -> fetchAndCacheToken(accountId, appKey, appSecret));
        } finally {
            lock.unlock();
        }
    }

    private String fetchAndCacheToken(UUID accountId, String appKey, String appSecret) {
        log.info("KIS 토큰 신규 발급: accountId={}", accountId);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = Map.of(
                    "grant_type", "client_credentials",
                    "appkey", appKey,
                    "appsecret", appSecret
            );

            // KIS OAuth 토큰 발급 요청 (RestTemplate 직접 호출 — KisHttpClient 경유 불가)
            TokenResponse response = kisRestTemplate.exchange(
                    kisBaseUrl + "/oauth2/tokenP",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    TokenResponse.class
            ).getBody();

            OffsetDateTime expiresAt = parseExpiry(response.accessTokenExpired());

            // 발급된 토큰을 account_id 기준으로 DB에 upsert
            kisTokenCachePort.saveToken(accountId, response.accessToken(), expiresAt);
            return response.accessToken();
        } catch (Account.InvalidKisKeyException e) {
            throw e; // KIS 키 검증 실패는 그대로 전파
        } catch (Exception e) {
            throw new KisApiException("KIS 토큰 발급 실패 accountId=" + accountId, e);
        }
    }

    // 만료 1분 전부터 무효 처리 — 경계값 만료 오류(EGW00123) 방지
    private OffsetDateTime threshold() {
        return OffsetDateTime.now(KST).plusMinutes(1);
    }

    OffsetDateTime parseExpiry(String raw) {
        // KST 문자열 "yyyy-MM-dd HH:mm:ss" → ZonedDateTime → OffsetDateTime(+09:00)
        LocalDateTime ldt = LocalDateTime.parse(raw, KIS_EXPIRY_FORMAT);
        return ldt.atZone(KST).toOffsetDateTime();
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("access_token_token_expired") String accessTokenExpired
    ) {}
}
