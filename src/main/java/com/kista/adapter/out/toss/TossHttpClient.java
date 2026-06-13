package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.port.out.TossTokenPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossHttpClient {

    private final RestTemplate tossRestTemplate;
    private final TossTokenPort tossTokenPort;
    @Value("${toss.base-url}")
    private final String baseUrl;

    // 계좌 컨텍스트 API용 — X-Tossinvest-Account 헤더 포함 (주문·잔고·매수가능금액)
    public <T> T get(String path, Account account, MultiValueMap<String, String> params, Class<T> responseType) {
        return executeGet(path, account, params, responseType, true);
    }

    // 계좌 헤더 불필요 API용 — 시세 조회·환율 등
    public <T> T getNoAccountHeader(String path, Account account, MultiValueMap<String, String> params, Class<T> responseType) {
        return executeGet(path, account, params, responseType, false);
    }

    public <T> T post(String path, Account account, Object body, Class<T> responseType) {
        HttpHeaders headers = buildHeaders(account);
        try {
            return tossRestTemplate.exchange(
                    baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType
            ).getBody();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                // 401 — 토큰 무효화 후 1회 재시도
                log.warn("Toss POST 401 — 토큰 무효화 후 재시도: path={}", path);
                tossTokenPort.invalidateToken(account.id());
                try {
                    return tossRestTemplate.exchange(
                            baseUrl + path, HttpMethod.POST,
                            new HttpEntity<>(body, buildHeaders(account)), responseType
                    ).getBody();
                } catch (RestClientException retryEx) {
                    throw new TossApiException("Toss API 토큰 재시도 실패: " + retryEx.getMessage(), retryEx);
                }
            }
            throw new TossApiException("Toss API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new TossApiException("Toss API 요청 실패: " + e.getMessage(), e);
        }
    }

    // DELETE 요청 — 주문 취소 등 (응답 body 없음)
    public void delete(String path, Account account) {
        HttpHeaders headers = buildHeaders(account);
        try {
            tossRestTemplate.exchange(
                    baseUrl + path, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class
            );
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                // 401 — 토큰 무효화 후 1회 재시도
                log.warn("Toss DELETE 401 — 토큰 무효화 후 재시도: path={}", path);
                tossTokenPort.invalidateToken(account.id());
                try {
                    tossRestTemplate.exchange(
                            baseUrl + path, HttpMethod.DELETE,
                            new HttpEntity<>(buildHeaders(account)), Void.class
                    );
                    return;
                } catch (RestClientException retryEx) {
                    throw new TossApiException("Toss API 토큰 재시도 실패: " + retryEx.getMessage(), retryEx);
                }
            }
            throw new TossApiException("Toss API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new TossApiException("Toss API 요청 실패: " + e.getMessage(), e);
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private <T> T executeGet(String path, Account account, MultiValueMap<String, String> params,
                              Class<T> responseType, boolean withAccountHeader) {
        HttpHeaders headers = withAccountHeader ? buildHeaders(account) : buildHeadersNoAccount(account);
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        try {
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                // 401 — 토큰 무효화 후 1회 재시도
                log.warn("Toss GET 401 — 토큰 무효화 후 재시도: path={}", path);
                tossTokenPort.invalidateToken(account.id());
                HttpHeaders freshHeaders = withAccountHeader ? buildHeaders(account) : buildHeadersNoAccount(account);
                try {
                    return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(freshHeaders), responseType).getBody();
                } catch (RestClientException retryEx) {
                    throw new TossApiException("Toss API 토큰 재시도 실패: " + retryEx.getMessage(), retryEx);
                }
            }
            throw new TossApiException("Toss API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new TossApiException("Toss API 요청 실패: " + e.getMessage(), e);
        }
    }

    // 계좌 컨텍스트 헤더 (X-Tossinvest-Account 포함) — Account.kisAccountType에 accountSeq가 저장됨
    private HttpHeaders buildHeaders(Account account) {
        String token = tossTokenPort.getToken(account.id(), account.kisAppKey(), account.kisSecretKey());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("X-Tossinvest-Account", account.kisAccountType());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // 계좌 헤더 미포함 — 시세 조회·환율 등 계좌 컨텍스트 불필요 API용
    private HttpHeaders buildHeadersNoAccount(Account account) {
        // kisAppKey/kisSecretKey 필드를 Toss clientId/clientSecret으로 재사용
        String token = tossTokenPort.getToken(account.id(), account.kisAppKey(), account.kisSecretKey());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
