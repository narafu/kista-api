package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.TossTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class TossHttpClient {

    private final RestTemplate tossRestTemplate;
    private final TossTokenPort tossTokenPort;
    @Value("${toss.base-url}")
    private final String baseUrl;

    // 계좌 컨텍스트 API용 — X-Tossinvest-Account 헤더 포함 (주문·잔고·매수가능금액)
    // Account.kisAccountType에 accountSeq가 저장됨
    public HttpHeaders buildHeaders(Account account) {
        String token = tossTokenPort.getToken(account.id(), account.kisAppKey(), account.kisSecretKey());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("X-Tossinvest-Account", account.kisAccountType());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // 계좌 헤더 불필요 API용 — 시세 조회 등
    public HttpHeaders buildHeadersNoAccount(Account account) {
        // kisAppKey/kisSecretKey 필드를 Toss clientId/clientSecret으로 재사용
        String token = tossTokenPort.getToken(account.id(), account.kisAppKey(), account.kisSecretKey());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public <T> T get(String path, HttpHeaders headers, MultiValueMap<String, String> params, Class<T> responseType) {
        String url = UriComponentsBuilder
                .fromUriString(baseUrl + path)
                .queryParams(params)
                .toUriString();
        return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
    }

    public <T> T get(String path, HttpHeaders headers, MultiValueMap<String, String> params,
                     ParameterizedTypeReference<T> responseType) {
        String url = UriComponentsBuilder
                .fromUriString(baseUrl + path)
                .queryParams(params)
                .toUriString();
        return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
    }

    public <T> T post(String path, HttpHeaders headers, Object body, Class<T> responseType) {
        return tossRestTemplate.exchange(
                baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType
        ).getBody();
    }

    // DELETE 요청 — 주문 취소 등 (응답 body 없음)
    public void delete(String path, HttpHeaders headers) {
        tossRestTemplate.exchange(
                baseUrl + path, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class
        );
    }
}
