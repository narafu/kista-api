package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.broker.adapter.out.internal.DbTokenCoordinator;
import com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator;
import com.kista.tradingcore.broker.application.port.output.BrokerConnectionTestPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

// 메리츠 토큰 발급·연결 테스트 — OAuth는 RestClient 직접 사용(MeritzHttpClient → 이 클래스 의존이라 순환 회피)
@Slf4j
@Component
@RequiredArgsConstructor
class MeritzAuthApi implements BrokerConnectionTestPort {

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); // expires_date(KST)
    static final String CURRENCIES_PATH = "/accounts/v1/me/overseas/currencies"; // 외화잔고 — 연결 테스트용 조회

    private final RestClient meritzRestClient;          // baseUrl 고정 (MeritzConfig)
    private final ObjectMapper objectMapper;            // 응답 본문 파싱
    // TokenCoordinator 구현 빈이 여럿이라 구체 타입 주입(KisAuthApi와 동일)
    private final DbTokenCoordinator tokenCoordinator;

    String getToken(BrokerAccountRef account) {
        return tokenCoordinator.obtain(account.id(), () -> issue(account.appKey(), account.secretKey()));
    }

    TokenCoordinator.RecoveredToken recoverToken(BrokerAccountRef account, String rejectedToken) {
        return tokenCoordinator.recover(account.id(), rejectedToken, () -> issue(account.appKey(), account.secretKey()));
    }

    // 만료는 expires_date(최초 발급 기준 절대시각)로 계산 — 재발급은 같은 토큰·같은 expires_date를 돌려주므로 expires_in(43200 고정)은 신뢰 불가
    TokenCoordinator.IssuedToken issue(String appKey, String secretKey) {
        Token token = issueToken(appKey, secretKey);
        return new TokenCoordinator.IssuedToken(token.accessToken(),
                Duration.between(OffsetDateTime.now(TimeZones.KST), token.expiresAt()).getSeconds());
    }

    @Override
    public Broker supports() {
        return Broker.MERITZ;
    }

    @Override
    public void verifyCredentials(String appKey, String secretKey) {
        issueToken(appKey, secretKey); // 토큰 발급 성공 여부만 확인 — 어느 계좌 캐시에도 저장하지 않는다
    }

    @Override
    public BrokerAccountVerification verifyAccount(String appKey, String secretKey, String accountNo) {
        // 메리츠는 앱키에 계좌가 종속 — accountNo 무시, 외화잔고 조회 성공으로 계좌 조회 권한 확인
        String token = issueToken(appKey, secretKey).accessToken();
        String body;
        try {
            body = meritzRestClient.get().uri(CURRENCIES_PATH)
                    .headers(h -> MeritzHttpClient.applyHeaders(h, token))
                    .exchange((req, res) -> MeritzHttpClient.readBody(res));
        } catch (RestClientException e) {
            throw new MeritzApiException(null, "Meritz 연결 테스트 조회 통신 실패", e, MeritzApiException.Conflict.NONE, false);
        }
        MeritzResponse res = MeritzResponse.parse(objectMapper, body, "GET " + CURRENCIES_PATH);
        if (!res.success()) {
            log.debug("Meritz 연결 테스트 조회 실패: rsp_cd={}, msg={}", res.rspCd(), res.rspMsg());
            if (MeritzResponse.RATE_LIMITED.equals(res.rspCd())) throw new BrokerRateLimitException(); // 한도 초과는 키 오류가 아님
            throw MeritzResponse.PERMISSION_DENIED_CODES.contains(res.rspCd())
                    ? new BrokerPermissionDeniedException() : new BrokerCredentialException();
        }
        return new BrokerAccountVerification(null, null);
    }

    // POST /oauth2/token — 실패 코드는 error_code 또는 rsp_cd, HTTP 403/500 어느 쪽이든 본문으로 판정
    private Token issueToken(String appKey, String secretKey) {
        if (appKey == null || appKey.isBlank() || secretKey == null || secretKey.isBlank()) throw new BrokerCredentialException();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", appKey);
        form.add("client_secret", secretKey);
        form.add("scope", "login");
        String body;
        try {
            body = meritzRestClient.post().uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((req, res) -> MeritzHttpClient.readBody(res));
        } catch (RestClientException e) {
            throw new MeritzApiException(null, "Meritz 토큰 발급 통신 실패", e, MeritzApiException.Conflict.NONE, false);
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            json = null;
        }
        String accessToken = text(json, "access_token");
        String expiresDate = text(json, "expires_date");
        if (accessToken != null && expiresDate != null) {
            try {
                return new Token(accessToken, LocalDateTime.parse(expiresDate, EXPIRY_FORMAT).atZone(TimeZones.KST).toOffsetDateTime());
            } catch (DateTimeParseException e) {
                // 만료일 형식 이상 — 발송 전 단계 실패로 분류되도록 MeritzApiException으로 감싼다
                throw new MeritzApiException(null, "Meritz 토큰 만료일 해석 불가", e, MeritzApiException.Conflict.NONE, false);
            }
        }
        String code = text(json, "error_code") != null ? text(json, "error_code") : text(json, "rsp_cd");
        log.warn("Meritz 토큰 발급 실패: code={}", code); // 본문엔 토큰이 없지만 메시지 원문은 로그에 남기지 않는다
        if (MeritzResponse.INVALID_CLIENT.equals(code)) throw new BrokerCredentialException();
        throw new MeritzApiException(code, "Meritz 토큰 발급 실패: code=" + code, null, MeritzApiException.Conflict.NONE, false);
    }

    private static String text(JsonNode json, String field) {
        if (json == null) return null;
        JsonNode node = json.get(field);
        return node == null || node.isNull() || node.asText().isEmpty() ? null : node.asText();
    }

    private record Token(String accessToken, OffsetDateTime expiresAt) {}
}
