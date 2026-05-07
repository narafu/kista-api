package com.kista.adapter.out.kis;

import com.kista.domain.model.Account;
import com.kista.domain.port.out.KisTokenPort;
import lombok.RequiredArgsConstructor;
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
public class KisHttpClient {

    private final RestTemplate kisRestTemplate;
    private final KisProperties props;
    private final KisTokenPort kisTokenPort;

    public KisProperties props() {
        return props;
    }

    // 계좌별 자격증명으로 헤더 구성 — 모든 KIS API 호출에 사용
    public HttpHeaders buildHeaders(String trId, Account account) {
        String token = kisTokenPort.getToken(account.id(), account.kisAppKey(), account.kisSecretKey());
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + token);
        headers.set("appkey", account.kisAppKey());
        headers.set("appsecret", account.kisSecretKey());
        headers.set("tr_id", trId);
        headers.set("custtype", "P");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public <T> T get(String path, HttpHeaders headers, MultiValueMap<String, String> params, Class<T> responseType) {
        String url = UriComponentsBuilder
                .fromUriString(props.baseUrl() + path)
                .queryParams(params)
                .toUriString();
        return kisRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
    }

    public <T> T post(String path, HttpHeaders headers, Object body, Class<T> responseType) {
        return kisRestTemplate.exchange(
                props.baseUrl() + path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType
        ).getBody();
    }
}
