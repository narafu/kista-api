package com.kista.adapter.out.kis;

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

    private final RestTemplate kisRestTemplate; // 빈 이름: kisRestTemplate
    private final KisProperties props;
    private final KisTokenPort kisTokenPort; // 매 KIS 호출 시 토큰 자동 검증+갱신

    public KisProperties props() {
        return props;
    }

    public HttpHeaders buildHeaders(String trId) {
        // 매 호출마다 토큰 유효성 검증 — 만료 시 자동 재발급
        String token = kisTokenPort.getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + token);
        headers.set("appkey", props.appKey());
        headers.set("appsecret", props.appSecret());
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
