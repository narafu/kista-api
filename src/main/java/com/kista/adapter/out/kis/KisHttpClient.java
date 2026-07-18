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
    public <T> T post(String trId, String path, Account account, Object body, Class<T> responseType) {
        return executeWithRetry(trId, account, headers -> post(path, headers, body, responseType));
    }

    // Trading API용: CANO/ACNT_PRDT_CD 자동 주입 + buildHeaders + get 일괄 처리
    public <T> T tradingGet(String trId, String path, Account account,
                            Class<T> responseType, Consumer<MultiValueMap<String, String>> extraParams) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        String[] parts = splitAccountNo(account.accountNo());
        params.add("CANO", parts[0]);
        params.add("ACNT_PRDT_CD", parts[1]);
        extraParams.accept(params);
        return executeWithRetry(trId, account, headers -> get(path, headers, params, responseType));
    }

    // 시세 API용: AUTH="" 기본 주입 + buildHeaders + get 일괄 처리 (계좌 파라미터 없음)
    public <T> T pricingGet(String trId, String path, Account account,
                            Class<T> responseType, Consumer<MultiValueMap<String, String>> extraParams) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");
        extraParams.accept(params);
        return executeWithRetry(trId, account, headers -> get(path, headers, params, responseType));
    }

    // 401 → 실패한 요청의 토큰만 조건부 무효화 후 최신 토큰으로 1회 재시도한다.
    // RestClientException은 KisApiException으로 래핑 → GlobalExceptionHandler 503
    private <T> T executeWithRetry(String trId, Account account, Function<HttpHeaders, T> call) {
        String rejectedToken = kisAuthApi.getToken(account.id(), account.appKey(), account.secretKey());
        try {
            return call.apply(buildHeaders(rejectedToken, account.appKey(), account.secretKey(), trId));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 401) {
                log.warn("KIS 401 — 토큰 무효화 후 재시도: accountId={}", account.id());
                kisAuthApi.invalidateToken(account.id(), rejectedToken);
                try {
                    // 무효화된 캐시 → buildHeaders가 신규 토큰을 재발급해 헤더 재구성
                    return call.apply(buildHeaders(trId, account));
                } catch (RestClientException retryEx) {
                    throw new KisApiException("KIS API 토큰 재시도 실패: " + retryEx.getMessage(), retryEx);
                }
            }
            throw new KisApiException("KIS API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new KisApiException("KIS API 요청 실패: " + e.getMessage(), e);
        }
    }
}
