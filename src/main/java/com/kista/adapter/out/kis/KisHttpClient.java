package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
class KisHttpClient {

    private final RestTemplate kisRestTemplate;
    private final KisAuthApi kisAuthApi; // 포트 대신 같은 패키지 구체 클래스 직접 주입
    @Value("${kis.base-url}")
    private final String baseUrl;

    // 계좌별 자격증명으로 헤더 구성 — 모든 KIS API 호출에 사용
    public HttpHeaders buildHeaders(String trId, Account account) {
        String token = kisAuthApi.getToken(account.id(), account.appKey(), account.secretKey());
        return buildHeaders(token, account.appKey(), account.secretKey(), trId);
    }

    // 토큰을 직접 보유한 호출부(KisAuthApi 등) 공용 헤더 빌더
    public static HttpHeaders buildHeaders(String token, String appKey, String appSecret, String trId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + token);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", trId);
        headers.set("custtype", "P");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // accountNo = "74420614-01" → [CANO, ACNT_PRDT_CD] 분리 — 구분자 없으면 상품코드 "01" 기본
    public static String[] splitAccountNo(String accountNo) {
        String[] parts = accountNo.split("-", 2);
        return new String[]{parts[0], parts.length > 1 ? parts[1] : "01"};
    }

    public <T> T get(String path, HttpHeaders headers, MultiValueMap<String, String> params, Class<T> responseType) {
        String url = UriComponentsBuilder
                .fromUriString(baseUrl + path)
                .queryParams(params)
                .toUriString();
        return kisRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
    }

    public <T> T post(String path, HttpHeaders headers, Object body, Class<T> responseType) {
        return kisRestTemplate.exchange(
                baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType
        ).getBody();
    }

    // 계좌 기반 POST — buildHeaders + post + 401 재시도 일괄 처리 (KisOrderApi 등)
    // 주문 접수/취소라 재시도 안 함(중복 주문 위험 배제) — 페이싱은 TradingOrderExecutor가 사전 예방
    public <T> T post(String trId, String path, Account account, Object body, Class<T> responseType) {
        return executeWithRetry(trId, account, false, headers -> post(path, headers, body, responseType));
    }

    // Trading API용: CANO/ACNT_PRDT_CD 자동 주입 + buildHeaders + get 일괄 처리
    public <T> T tradingGet(String trId, String path, Account account,
                            Class<T> responseType, Consumer<MultiValueMap<String, String>> extraParams) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        String[] parts = splitAccountNo(account.accountNo());
        params.add("CANO", parts[0]);
        params.add("ACNT_PRDT_CD", parts[1]);
        extraParams.accept(params);
        return executeWithRetry(trId, account, true, headers -> get(path, headers, params, responseType));
    }

    // 시세 API용: AUTH="" 기본 주입 + buildHeaders + get 일괄 처리 (계좌 파라미터 없음)
    public <T> T pricingGet(String trId, String path, Account account,
                            Class<T> responseType, Consumer<MultiValueMap<String, String>> extraParams) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");
        extraParams.accept(params);
        return executeWithRetry(trId, account, true, headers -> get(path, headers, params, responseType));
    }

    private static final long RATE_LIMIT_BACKOFF_BASE_MILLIS = 1000; // KIS 초당 거래건수 제한(EGW00201) 백오프 기준 — 실전계좌 20건/초 문서 기준, 최소 1초 대기
    private static final long RATE_LIMIT_JITTER_MILLIS = 200;        // 동시 재시도가 같은 초에 재정렬되는 것을 완화하는 지터
    private static final int MAX_RATE_LIMIT_RETRIES = 2;

    // 401 → 실패한 요청의 토큰만 조건부 무효화 후 최신 토큰으로 1회 재시도한다.
    // retryOnRateLimit=true: 조회(GET) 전용 — 안전하게 재시도 가능. false: 주문 접수/취소(POST) — 재시도 안 함(사전 페이싱으로 별도 대응)
    // RestClientException은 KisApiException으로 래핑 → GlobalExceptionHandler 503
    private <T> T executeWithRetry(String trId, Account account, boolean retryOnRateLimit, Function<HttpHeaders, T> call) {
        String token = kisAuthApi.getToken(account.id(), account.appKey(), account.secretKey());
        for (int attempt = 0; ; attempt++) {
            try {
                return call.apply(buildHeaders(token, account.appKey(), account.secretKey(), trId));
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 401) {
                    log.warn("KIS 401 — 토큰 무효화 후 재시도: accountId={}", account.id());
                    String recoveredToken = kisAuthApi.recoverToken(
                            account.id(), account.appKey(), account.secretKey(), token).accessToken();
                    try {
                        return call.apply(buildHeaders(recoveredToken, account.appKey(), account.secretKey(), trId));
                    } catch (RestClientException retryEx) {
                        throw new KisApiException("KIS API 토큰 재시도 실패: " + retryEx.getMessage(), retryEx);
                    }
                }
                if (retryOnRateLimit && isRateLimited(e) && attempt < MAX_RATE_LIMIT_RETRIES) {
                    log.warn("KIS EGW00201 — 초당 거래건수 초과, 백오프 후 재시도 {}/{}: accountId={}",
                            attempt + 1, MAX_RATE_LIMIT_RETRIES, account.id());
                    if (!sleepRateLimitBackoff(attempt)) {
                        // 대기 중 인터럽트(배포·강제종료) — 백오프 없이 즉시 재시도하면 재발 위험만 커지므로 포기
                        throw new KisApiException("KIS API 오류(재시도 대기 중 인터럽트): "
                                + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
                    }
                    continue; // 토큰 문제가 아니므로 같은 토큰으로 재시도
                }
                throw new KisApiException("KIS API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            } catch (RestClientException e) {
                throw new KisApiException("KIS API 요청 실패: " + e.getMessage(), e);
            }
        }
    }

    // KIS 게이트웨이가 초당 거래건수 제한으로 접수 전 거부한 응답 — msg_cd 정확 매칭(다른 오류 메시지 오탐 방지, Toss의 code 문자열 매칭과 동일 패턴)
    private boolean isRateLimited(HttpStatusCodeException e) {
        return e.getResponseBodyAsString().contains("\"msg_cd\":\"EGW00201\"");
    }

    // 지수 백오프 + 지터 — 대기 중 인터럽트되면 false 반환(호출측이 재시도를 포기하고 즉시 실패 처리)
    private boolean sleepRateLimitBackoff(int attempt) {
        long delay = RATE_LIMIT_BACKOFF_BASE_MILLIS * (1L << attempt) + (long) (Math.random() * RATE_LIMIT_JITTER_MILLIS);
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
