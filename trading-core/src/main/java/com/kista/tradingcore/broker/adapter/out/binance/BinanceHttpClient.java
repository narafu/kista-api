package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.tradingcore.broker.domain.model.*;
import com.kista.tradingcore.broker.domain.model.binance.BinanceApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// 바이낸스 현물 REST 공통 — 모든 파라미터는 query(POST 포함, body 비움), HMAC-SHA256 서명, 오류 code → 벤더 중립 예외 매핑
@Slf4j
@Component
class BinanceHttpClient {

    private static final int RECV_WINDOW_MS = 5000;                            // 서명 요청 허용 시차
    private static final Set<Integer> CREDENTIAL_CODES = Set.of(-2014, -2015, -1022); // 키 형식·권한·서명 오류 (-2015는 키·IP·권한 구분 불가)
    private static final Set<Integer> UNKNOWN_CODES = Set.of(-1006, -1007);    // 실행 상태 불명 — 실패 취급 금지

    private final RestClient binanceRestClient; // baseUrl 없는 RestClient (BinanceConfig)
    private final ObjectMapper objectMapper;   // 오류 바디 파싱
    private final String baseUrl;              // binance.base-url (테스트넷 전환용)
    private final Clock clock;                 // timestamp 원천 — 테스트에서 고정

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패(Toss Redis Store 장애 교훈)
    BinanceHttpClient(RestClient binanceRestClient, ObjectMapper objectMapper,
                      @Value("${binance.base-url:https://api.binance.com}") String baseUrl) {
        this(binanceRestClient, objectMapper, baseUrl, Clock.systemUTC());
    }

    BinanceHttpClient(RestClient binanceRestClient, ObjectMapper objectMapper, String baseUrl, Clock clock) {
        this.binanceRestClient = binanceRestClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    // 공개 시세·exchangeInfo — 서명 없음
    <T> T getPublic(String path, LinkedHashMap<String, String> params, ParameterizedTypeReference<T> type) {
        String qs = queryString(params);
        URI uri = URI.create(baseUrl + path + (qs.isEmpty() ? "" : "?" + qs));
        return execute("GET " + path, () -> binanceRestClient.get().uri(uri).retrieve().body(type));
    }

    <T> T getSigned(String path, LinkedHashMap<String, String> params, BrokerAccountRef auth, ParameterizedTypeReference<T> type) {
        URI uri = signedUri(path, params, auth);
        return execute("GET " + path, () -> binanceRestClient.get().uri(uri)
                .header("X-MBX-APIKEY", auth.appKey()).retrieve().body(type));
    }

    // POST도 파라미터는 query, body 없음
    <T> T postSigned(String path, LinkedHashMap<String, String> params, BrokerAccountRef auth, ParameterizedTypeReference<T> type) {
        URI uri = signedUri(path, params, auth);
        return execute("POST " + path, () -> binanceRestClient.post().uri(uri)
                .header("X-MBX-APIKEY", auth.appKey()).retrieve().body(type));
    }

    // payload = params + recvWindow + timestamp 순서, 서명 대상은 퍼센트 인코딩된 그 문자열
    private URI signedUri(String path, LinkedHashMap<String, String> params, BrokerAccountRef auth) {
        if (isBlank(auth.appKey()) || isBlank(auth.secretKey())) throw new BrokerCredentialException();
        LinkedHashMap<String, String> all = new LinkedHashMap<>(params);
        all.put("recvWindow", String.valueOf(RECV_WINDOW_MS));
        all.put("timestamp", String.valueOf(clock.millis()));
        String payload = queryString(all);
        // 절대 URI 그대로 전송 — RestClient 템플릿 재인코딩 방지
        return URI.create(baseUrl + path + "?" + payload + "&signature=" + sign(auth.secretKey(), payload));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String sign(String secretKey, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 서명 실패", e);
        }
    }

    // 퍼센트 인코딩한 문자열이 곧 서명 대상(2026-01 -1022 규칙)
    static String queryString(LinkedHashMap<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private <T> T execute(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpStatusCodeException e) {
            throw translate(e, action);
        } catch (RestClientException e) {
            // I/O 실패 — 접수 여부 불명(rejected=false)
            // 메시지에 서명 포함 요청 URI가 실리므로 로그에는 클래스명만, cause는 URI 없는 최하위 원인만 남긴다
            Throwable root = e.getMostSpecificCause();
            log.warn("Binance {} 통신 실패: {} ({})", action, e.getClass().getSimpleName(), root.getClass().getSimpleName());
            throw new BinanceApiException(0, null, "Binance " + action + " 통신 실패", root == e ? null : root, false);
        }
    }

    // 위에서부터 첫 매치. 원문 바디는 로그에만
    private RuntimeException translate(HttpStatusCodeException e, String action) {
        int status = e.getStatusCode().value();
        Integer code = errorCode(e.getResponseBodyAsString());
        log.warn("Binance {} 실패: status={}, code={}, body={}", action, status, code, e.getResponseBodyAsString());
        if (status == 429 || status == 418) return new BrokerRateLimitException();
        if (code != null && CREDENTIAL_CODES.contains(code)) return new BrokerCredentialException();
        String message = "Binance " + action + " 실패: status=" + status + ", code=" + code;
        if (status >= 500 || (code != null && UNKNOWN_CODES.contains(code))) {
            return new BinanceApiException(status, code, message, e, false); // 실행 상태 불명
        }
        if (Integer.valueOf(-1021).equals(code)) {
            // ponytail: 서버 시각 동기화 없음 — 로그에 -1021이 보이면 /api/v3/time 오프셋 보정 추가
            log.error("Binance {} timestamp 범위 초과(-1021): 서버 시각 확인 필요", action);
        }
        return new BinanceApiException(status, code, message, e, true);
    }

    private Integer errorCode(String body) {
        try {
            JsonNode code = objectMapper.readTree(body).path("code");
            return code.isIntegralNumber() ? code.asInt() : null;
        } catch (RuntimeException ex) {
            return null; // 바디가 JSON이 아님(WAF HTML 등) — 상태 코드만으로 판정
        }
    }
}
