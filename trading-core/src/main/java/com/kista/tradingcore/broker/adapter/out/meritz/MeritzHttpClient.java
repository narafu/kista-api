package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.tradingcore.broker.adapter.out.internal.CallIntervalGate;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// 메리츠 REST 공통 — 상태코드 무관 본문 rsp_cd 판정, 토큰 무효 1회 재발급 재시도, GET 한도 백오프, 앱키별 호출 간격
@Slf4j
@Component
class MeritzHttpClient {

    static final String MAC_ADDRESS = "0242AC110002"; // 필수 헤더 — 값 검증 없음(실측), 고정 상수
    private static final int MAX_RATE_LIMIT_RETRIES = 2;     // EGW00200 GET 재시도 상한
    private static final long RATE_LIMIT_JITTER_MILLIS = 200; // 동시 재시도 분산
    private static final int MAX_PAGES = 50;                  // 연속조회 상한 — 키 오류로 무한 반복 방지

    private final RestClient meritzRestClient; // baseUrl 고정 (MeritzConfig)
    private final MeritzAuthApi authApi;       // 같은 패키지 구체 클래스 — 토큰 확보·복구
    private final ObjectMapper objectMapper;   // 본문 파싱·행 변환
    private final CallIntervalGate callGate;   // 앱키 단위 최소 간격(110ms ≈ 9 TPS, 최대 대기 20s)
    private final long backoffBaseMillis;      // EGW00200 백오프 기준(1s → 2s) — 테스트에서 축소

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패
    MeritzHttpClient(RestClient meritzRestClient, MeritzAuthApi authApi, ObjectMapper objectMapper) {
        this(meritzRestClient, authApi, objectMapper, new CallIntervalGate(110, 20_000), 1_000);
    }

    MeritzHttpClient(RestClient meritzRestClient, MeritzAuthApi authApi, ObjectMapper objectMapper,
                     CallIntervalGate callGate, long backoffBaseMillis) {
        this.meritzRestClient = meritzRestClient;
        this.authApi = authApi;
        this.objectMapper = objectMapper;
        this.callGate = callGate;
        this.backoffBaseMillis = backoffBaseMillis;
    }

    // 공통 헤더 — 토큰을 직접 가진 호출부(MeritzAuthApi.verifyAccount)도 사용
    static void applyHeaders(HttpHeaders headers, String token) {
        headers.set("authorization", "Bearer " + token);
        headers.set("mac_address", MAC_ADDRESS);
    }

    // 상태코드와 무관하게 본문 문자열을 읽는다 — 기본 retrieve()는 500에서 본문 판정 전에 예외를 던진다
    static String readBody(ClientHttpResponse response) throws IOException {
        return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    MeritzResponse get(String path, BrokerAccountRef account, MultiValueMap<String, String> params) {
        return execute("GET " + path, account, true, token -> meritzRestClient.get()
                .uri(b -> b.path(path).queryParams(params).build())
                .headers(h -> applyHeaders(h, token))
                .exchange((req, res) -> readBody(res)));
    }

    // 주문·취소 — 토큰 무효 외 자동 재시도 없음(중복 주문 방지)
    MeritzResponse post(String path, BrokerAccountRef account, Object body) {
        return execute("POST " + path, account, false, token -> meritzRestClient.post().uri(path)
                .headers(h -> applyHeaders(h, token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> readBody(res)));
    }

    // 연속조회 — rsp_cd 5762일 때만 tr_cont=1 + tr_cont_key(원문 그대로)로 이어 조회
    <T> List<T> getAllPages(String path, BrokerAccountRef account, MultiValueMap<String, String> params, Class<T> rowType) {
        MultiValueMap<String, String> pageParams = new LinkedMultiValueMap<>(params);
        List<T> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            MeritzResponse res = get(path, account, pageParams);
            rows.addAll(rowList(res, rowType));
            if (!res.hasNextPage()) return rows;
            if (page >= MAX_PAGES) {
                log.warn("Meritz GET {} 연속조회 {}페이지 상한 도달 — 누적 {}건만 반환: accountId={}", path, MAX_PAGES, rows.size(), account.id());
                return rows;
            }
            pageParams.set("tr_cont", "1");
            pageParams.set("tr_cont_key", res.trContKey());
        }
    }

    // data 단건 객체 → T (없으면 null)
    <T> T rows(MeritzResponse res, Class<T> type) {
        JsonNode data = res.data();
        return data == null || data.isNull() || data.isMissingNode() ? null : objectMapper.treeToValue(data, type);
    }

    // data 배열 → List<T> (없음·빈 값 → 빈 리스트, 단건 객체면 1건)
    <T> List<T> rowList(MeritzResponse res, Class<T> rowType) {
        JsonNode data = res.data();
        if (data == null || data.isNull() || data.isMissingNode()) return List.of();
        if (!data.isArray()) return data.isObject() ? List.of(objectMapper.treeToValue(data, rowType)) : List.of();
        List<T> rows = new ArrayList<>(data.size());
        for (JsonNode row : data) rows.add(objectMapper.treeToValue(row, rowType));
        return rows;
    }

    private MeritzResponse execute(String action, BrokerAccountRef account, boolean idempotent, Function<String, String> call) {
        String token = obtainToken(account);
        boolean tokenRetried = false;
        int rateLimitRetries = 0;
        while (true) {
            awaitSlot(account); // 매 시도 전 간격 확보 — 토큰 복구가 캐시 적중으로 즉시 끝나도 밀착 발사 방지
            MeritzResponse res = send(action, call, token);
            String code = res.rspCd();
            if (MeritzResponse.TOKEN_INVALID_CODES.contains(code)) {
                // 토큰 거절은 게이트웨이 단계라 미접수 확정 — POST도 1회 재전송 안전
                if (tokenRetried) throw MeritzApiException.rejected(code, "Meritz " + action + " 토큰 재발급 후에도 거절: rsp_cd=" + code);
                log.warn("Meritz {} 토큰 무효({}) — 재발급 후 재시도: accountId={}", action, code, account.id());
                token = recoverToken(account, token);
                tokenRetried = true;
                continue;
            }
            if (MeritzResponse.RATE_LIMITED.equals(code)) {
                if (!idempotent || rateLimitRetries >= MAX_RATE_LIMIT_RETRIES) throw new BrokerRateLimitException();
                log.warn("Meritz {} 초당 한도(EGW00200) — 백오프 후 재시도 {}/{}: accountId={}",
                        action, rateLimitRetries + 1, MAX_RATE_LIMIT_RETRIES, account.id());
                sleepBackoff(rateLimitRetries++, action);
                continue;
            }
            if (!res.success()) {
                log.warn("Meritz {} 실패: rsp_cd={}, msg={} / {}", action, code, res.rspMsg(), res.rspSubMsg());
                throw res.failure(action);
            }
            return res;
        }
    }

    private MeritzResponse send(String action, Function<String, String> call, String token) {
        String body;
        try {
            body = call.apply(token);
        } catch (RestClientException e) {
            // I/O·타임아웃 — 접수 여부 불명
            log.warn("Meritz {} 통신 실패: {}", action, e.getMessage());
            throw new MeritzApiException(null, "Meritz " + action + " 통신 실패", e, false);
        }
        return MeritzResponse.parse(objectMapper, body, action);
    }

    // 토큰 확보 실패는 송신 전이라 확정 거절 — 자격증명·권한·한도 예외는 타입 그대로 전파
    private String obtainToken(BrokerAccountRef account) {
        try {
            return authApi.getToken(account);
        } catch (MeritzApiException e) {
            throw MeritzApiException.rejected(e.rspCd(), "Meritz 토큰 확보 실패(송신 전): " + e.getMessage());
        }
    }

    private String recoverToken(BrokerAccountRef account, String rejected) {
        try {
            return authApi.recoverToken(account, rejected).accessToken();
        } catch (MeritzApiException e) {
            throw MeritzApiException.rejected(e.rspCd(), "Meritz 토큰 복구 실패(재송신 전): " + e.getMessage());
        }
    }

    // 대기 상한 초과·인터럽트는 송신 전 실패라 확정 거절
    private void awaitSlot(BrokerAccountRef account) {
        callGate.await(account.appKey(), reason -> MeritzApiException.rejected(null,
                "Meritz 호출 대기열 과다/인터럽트 — 요청 거부: " + reason + " accountId=" + account.id()));
    }

    // 지수 백오프(1s·2s) + 지터 — 인터럽트되면 재시도 포기(확정 거절, 미송신)
    private void sleepBackoff(int attempt, String action) {
        long delay = backoffBaseMillis * (1L << attempt) + (long) (Math.random() * Math.min(RATE_LIMIT_JITTER_MILLIS, backoffBaseMillis));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw MeritzApiException.rejected(MeritzResponse.RATE_LIMITED, "Meritz " + action + " 한도 재시도 대기 중 인터럽트");
        }
    }
}
