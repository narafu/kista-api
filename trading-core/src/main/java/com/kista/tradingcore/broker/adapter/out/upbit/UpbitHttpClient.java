package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.tradingcore.broker.domain.model.*;
import com.kista.tradingcore.broker.domain.model.upbit.UpbitApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// 업비트 REST 공통 — 요청마다 HS512 JWT 서명(토큰 캐시 없음), 오류 바디 error.name → 벤더 중립 예외 매핑
@Slf4j
@Component
class UpbitHttpClient {

    private static final String JWT_HEADER = "{\"alg\":\"HS512\",\"typ\":\"JWT\"}"; // 업비트 권장 HS512
    private static final Set<String> CREDENTIAL_ERRORS = Set.of("invalid_access_key", "jwt_verification", "expired_access_key"); // 422 BROKER_CREDENTIAL_INVALID

    private final RestClient upbitRestClient;     // baseUrl 고정 RestClient (UpbitConfig)
    private final ObjectMapper objectMapper;      // JWT payload 직렬화·오류 바디 파싱
    private final Supplier<String> nonceSupplier; // 요청마다 새 nonce — 테스트에서 고정값 주입

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패(Toss Redis Store 장애 교훈)
    UpbitHttpClient(RestClient upbitRestClient, ObjectMapper objectMapper) {
        this(upbitRestClient, objectMapper, () -> UUID.randomUUID().toString());
    }

    UpbitHttpClient(RestClient upbitRestClient, ObjectMapper objectMapper, Supplier<String> nonceSupplier) {
        this.upbitRestClient = upbitRestClient;
        this.objectMapper = objectMapper;
        this.nonceSupplier = nonceSupplier;
    }

    // 공개 시세(티커·캔들) — 인증 없음
    <T> T getPublic(String path, LinkedHashMap<String, String> query, ParameterizedTypeReference<T> type) {
        return execute("GET " + path, () -> upbitRestClient.get().uri(withQuery(path, query)).retrieve().body(type));
    }

    // 인증 GET — query_hash는 실제 요청과 같은 비인코딩 쿼리 문자열
    <T> T get(String path, LinkedHashMap<String, String> query, BrokerAccountRef auth, ParameterizedTypeReference<T> type) {
        String bearer = "Bearer " + token(auth.appKey(), auth.secretKey(), queryString(query));
        return execute("GET " + path, () -> upbitRestClient.get().uri(withQuery(path, query))
                .header("Authorization", bearer).retrieve().body(type));
    }

    // 인증 POST — JSON 본문과 query_hash를 같은 순서의 key=value 쌍에서 만든다
    <T> T post(String path, LinkedHashMap<String, String> body, BrokerAccountRef auth, ParameterizedTypeReference<T> type) {
        String bearer = "Bearer " + token(auth.appKey(), auth.secretKey(), queryString(body));
        return execute("POST " + path, () -> upbitRestClient.post().uri(path)
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(type));
    }

    // JWT(HS512) — queryString이 비면 query_hash 생략
    String token(String accessKey, String secretKey, String queryString) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("access_key", accessKey);
        payload.put("nonce", nonceSupplier.get());
        if (!queryString.isEmpty()) {
            payload.put("query_hash", sha512Hex(queryString));
            payload.put("query_hash_alg", "SHA512");
        }
        String signingInput = base64Url(JWT_HEADER.getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(hmacSha512(secretKey, signingInput));
    }

    private <T> T execute(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpStatusCodeException e) {
            throw translate(e, action);
        } catch (RestClientException e) {
            // I/O 실패 — 접수 여부 불명(rejected=false)
            log.warn("Upbit {} 통신 실패: {}", action, e.getMessage());
            throw new UpbitApiException(0, null, "Upbit " + action + " 통신 실패", e, false);
        }
    }

    // error.name → 벤더 중립 예외. 원문 바디는 로그에만
    private RuntimeException translate(HttpStatusCodeException e, String action) {
        int status = e.getStatusCode().value();
        String name = errorName(e.getResponseBodyAsString());
        log.warn("Upbit {} 실패: status={}, name={}, body={}", action, status, name, e.getResponseBodyAsString());
        if (status == 429 || status == 418) return new BrokerRateLimitException();
        if ("no_authorization_ip".equals(name)) return new BrokerIpNotAllowedException();
        if ("out_of_scope".equals(name)) return new BrokerPermissionDeniedException();
        if (name != null && CREDENTIAL_ERRORS.contains(name)) return new BrokerCredentialException();
        return new UpbitApiException(status, name, "Upbit " + action + " 실패: status=" + status + ", name=" + name,
                e, status >= 400 && status < 500);
    }

    private String errorName(String body) {
        try {
            JsonNode name = objectMapper.readTree(body).path("error").path("name");
            return name.isMissingNode() || name.isNull() ? null : name.asString();
        } catch (RuntimeException ex) {
            return null; // 바디가 JSON이 아님 — 상태 코드만으로 판정
        }
    }

    private static String withQuery(String path, LinkedHashMap<String, String> query) {
        String qs = queryString(query);
        return qs.isEmpty() ? path : path + "?" + qs;
    }

    // 비인코딩 key=value&... — 값은 마켓 코드·UUID·숫자 문자열이라 인코딩 대상 문자가 없다
    private static String queryString(LinkedHashMap<String, String> pairs) {
        return pairs.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }

    private static String sha512Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-512 미지원", e);
        }
    }

    // JDK Mac — Nimbus MACSigner는 HS512에 512bit 미만 키를 거부하므로 쓰지 않는다
    private static byte[] hmacSha512(String secretKey, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA512 서명 실패", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
