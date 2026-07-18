package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
class TossHttpClient {

    private final RestTemplate tossRestTemplate;
    private final TossAuthApi tossAuthApi; // 포트 대신 같은 패키지 구체 클래스 직접 주입
    @Value("${toss.base-url}")
    private final String baseUrl;

    // 계좌 컨텍스트 API용 — X-Tossinvest-Account 헤더 포함 (주문·잔고·매수가능금액)
    public <T> T get(String path, Account account, MultiValueMap<String, String> params, Class<T> responseType) {
        return executeGet(path, account, params, responseType, true);
    }

    // 계좌 헤더 불필요 API용 — 시세 조회·환율 등 (개별 계좌 토큰 사용)
    public <T> T getNoAccountHeader(String path, Account account, MultiValueMap<String, String> params, Class<T> responseType) {
        return executeGet(path, account, params, responseType, false);
    }

    // 공통 API용 — 관리자 토큰 사용, 계좌 컨텍스트 불필요 (시세·환율·캔들·시장정보)
    public <T> T getCommon(String path, MultiValueMap<String, String> params, Class<T> responseType) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeCommon(path, () -> {
            // 매 호출(재시도 포함)마다 토큰을 다시 읽어 무효화 후 재발급 반영
            HttpHeaders headers = buildAdminHeaders();
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
        });
    }

    public <T> T post(String path, Account account, Object body, Class<T> responseType) {
        return executeWithRetry(account, path, token -> tossRestTemplate.exchange(
                baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, buildHeaders(account, token)), responseType
        ).getBody());
    }

    // ParameterizedTypeReference 오버로드 — 제네릭 래퍼 타입(TossResult<T> 등) 역직렬화용

    // 계좌 컨텍스트 API용 (ParameterizedTypeReference 버전)
    public <T> T get(String path, Account account, MultiValueMap<String, String> params,
                     ParameterizedTypeReference<T> typeRef) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithRetry(account, path, token -> {
            HttpHeaders headers = buildHeaders(account, token);
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), typeRef).getBody();
        });
    }

    // 계좌 헤더 불필요 API용 (ParameterizedTypeReference 버전)
    public <T> T getNoAccountHeader(String path, Account account, MultiValueMap<String, String> params,
                                    ParameterizedTypeReference<T> typeRef) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithRetry(account, path, token -> {
            HttpHeaders headers = buildHeadersNoAccount(token);
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), typeRef).getBody();
        });
    }

    // 공통 API용 (ParameterizedTypeReference 버전)
    public <T> T getCommon(String path, MultiValueMap<String, String> params,
                           ParameterizedTypeReference<T> typeRef) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeCommon(path, () -> {
            HttpHeaders headers = buildAdminHeaders();
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), typeRef).getBody();
        });
    }

    // POST 요청 (ParameterizedTypeReference 버전)
    public <T> T post(String path, Account account, Object body, ParameterizedTypeReference<T> typeRef) {
        return executeWithRetry(account, path, token -> tossRestTemplate.exchange(
                baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, buildHeaders(account, token)), typeRef
        ).getBody());
    }

    // DELETE 요청 — 주문 취소 등 (응답 body 없음)
    public void delete(String path, Account account) {
        executeWithRetry(account, path, token -> {
            tossRestTemplate.exchange(
                    baseUrl + path, HttpMethod.DELETE,
                    new HttpEntity<>(buildHeaders(account, token)), Void.class
            );
            return null;
        });
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private <T> T executeGet(String path, Account account, MultiValueMap<String, String> params,
                              Class<T> responseType, boolean withAccountHeader) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithRetry(account, path, token -> {
            HttpHeaders headers = withAccountHeader ? buildHeaders(account, token) : buildHeadersNoAccount(token);
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
        });
    }

    // 401 → 실패한 요청의 토큰만 조건부 무효화 후 최신 토큰으로 1회 재시도한다.
    private <T> T executeWithRetry(Account account, String path, java.util.function.Function<String, T> call) {
        String rejectedToken = tossAuthApi.getToken(account.id(), account.appKey(), account.secretKey());
        try {
            return call.apply(rejectedToken);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                log.warn("Toss 401 — 토큰 무효화 후 재시도: path={}", path);
                tossAuthApi.invalidateToken(account.id(), rejectedToken);
                try {
                    String retryToken = tossAuthApi.getToken(account.id(), account.appKey(), account.secretKey());
                    return call.apply(retryToken);
                } catch (RestClientException retryEx) {
                    throw new TossApiException("Toss API 토큰 재시도 실패: " + retryEx.getMessage(), retryEx);
                }
            }
            throw new TossApiException("Toss API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new TossApiException("Toss API 요청 실패: " + e.getMessage(), e);
        }
    }

    // 관리자 토큰 헤더 — X-Tossinvest-Account 없이 Bearer 토큰만
    private HttpHeaders buildAdminHeaders() {
        String token = tossAuthApi.getAdminToken();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // 공통 API 재시도 — 401 시 관리자 토큰 무효화 후 1회 재시도
    private <T> T executeCommon(String path, java.util.function.Supplier<T> call) {
        return execute401Retry(call, () -> {
            log.warn("Toss 관리자 토큰 401 — 무효화 후 재시도: path={}", path);
            tossAuthApi.invalidateAdminToken();
        });
    }

    // 401 재시도 공통 구조 — on401이 토큰 무효화를 담당 (계좌/관리자 분기)
    private <T> T execute401Retry(java.util.function.Supplier<T> call, Runnable on401) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                on401.run();
                try {
                    return call.get();
                } catch (RestClientException retryEx) {
                    throw new TossApiException("Toss API 토큰 재시도 실패: " + retryEx.getMessage(), retryEx);
                }
            }
            throw new TossApiException("Toss API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new TossApiException("Toss API 요청 실패: " + e.getMessage(), e);
        }
    }

    // 계좌 컨텍스트 헤더 (X-Tossinvest-Account 포함) — Account.brokerAccountCode에 accountSeq가 저장됨
    private HttpHeaders buildHeaders(Account account, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("X-Tossinvest-Account", account.brokerAccountCode());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // 계좌 헤더 미포함 — 시세 조회·환율 등 계좌 컨텍스트 불필요 API용
    private HttpHeaders buildHeadersNoAccount(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
