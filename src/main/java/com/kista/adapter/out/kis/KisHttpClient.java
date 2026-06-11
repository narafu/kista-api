package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.KisTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class KisHttpClient {

    private final RestTemplate kisRestTemplate;
    private final KisTokenPort kisTokenPort;
    @Value("${kis.base-url}")
    private final String baseUrl;

    // 계좌별 자격증명으로 헤더 구성 — 모든 KIS API 호출에 사용
    public HttpHeaders buildHeaders(String trId, Account account) {
        String token = kisTokenPort.getToken(account.id(), account.kisAppKey(), account.kisSecretKey());
        return buildHeaders(token, account.kisAppKey(), account.kisSecretKey(), trId);
    }

    // 토큰을 직접 보유한 호출부(KisAuthApi 등 KisTokenPort 미경유) 공용 헤더 빌더
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

    // Trading API용: CANO/ACNT_PRDT_CD 자동 주입 + buildHeaders + get 일괄 처리
    public <T> T tradingGet(String trId, String path, Account account,
                            Class<T> responseType, Consumer<MultiValueMap<String, String>> extraParams) {
        HttpHeaders headers = buildHeaders(trId, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());
        extraParams.accept(params);
        return get(path, headers, params, responseType);
    }

    // 시세 API용: AUTH="" 기본 주입 + buildHeaders + get 일괄 처리 (계좌 파라미터 없음)
    public <T> T pricingGet(String trId, String path, Account account,
                            Class<T> responseType, Consumer<MultiValueMap<String, String>> extraParams) {
        HttpHeaders headers = buildHeaders(trId, account);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("AUTH", "");
        extraParams.accept(params);
        return get(path, headers, params, responseType);
    }
}
