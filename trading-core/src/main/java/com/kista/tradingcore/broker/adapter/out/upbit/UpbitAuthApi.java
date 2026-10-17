package com.kista.tradingcore.broker.adapter.out.upbit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.application.port.output.BrokerConnectionTestPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

// 업비트 연결 테스트 — /v1/accounts로 키·허용 IP·자산조회 권한 검증, /v1/api_keys로 해당 키 만료일 조회(토큰 캐시 없음)
@Component
@RequiredArgsConstructor
class UpbitAuthApi implements BrokerConnectionTestPort {

    private final UpbitHttpClient http; // JWT 서명·오류 매핑 공통

    @Override
    public Broker supports() {
        return Broker.UPBIT;
    }

    @Override
    public void verifyCredentials(String appKey, String secretKey, UUID accountId) {
        // 등록 계좌 id만으로 호출돼 키가 비면 서명 전에 거부 — null 서명 방지
        if (appKey == null || appKey.isBlank() || secretKey == null || secretKey.isBlank()) throw new BrokerCredentialException();
        fetchAccounts(auth(appKey, secretKey)); // 실패는 UpbitHttpClient가 422(IP/권한/키)·429·503으로 매핑
    }

    @Override
    public BrokerAccountVerification verifyAccount(String appKey, String secretKey, String accountNo) {
        // 업비트는 계좌번호가 없다 — accountNo 무시. 주문하기 권한은 실주문 없이 확인할 수 없어 검증 밖
        BrokerAccountRef auth = auth(appKey, secretKey);
        fetchAccounts(auth);
        List<ApiKeyRow> keys = http.get("/v1/api_keys", new LinkedHashMap<>(), auth, new ParameterizedTypeReference<>() {});
        Instant expiresAt = keys.stream()
                .filter(k -> appKey.equals(k.accessKey()) && k.expireAt() != null)
                .map(k -> OffsetDateTime.parse(k.expireAt()).toInstant()) // "2026-06-25T11:22:54+09:00"
                .findFirst().orElse(null);
        return new BrokerAccountVerification(null, expiresAt);
    }

    private void fetchAccounts(BrokerAccountRef auth) {
        http.get("/v1/accounts", new LinkedHashMap<>(), auth, new ParameterizedTypeReference<List<Object>>() {});
    }

    private static BrokerAccountRef auth(String appKey, String secretKey) {
        return new BrokerAccountRef(null, appKey, secretKey, null, null, Broker.UPBIT);
    }

    record ApiKeyRow(@JsonProperty("access_key") String accessKey, @JsonProperty("expire_at") String expireAt) {}
}
