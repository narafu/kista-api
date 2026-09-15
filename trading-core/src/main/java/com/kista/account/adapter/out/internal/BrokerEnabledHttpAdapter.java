package com.kista.account.adapter.out.internal;

import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.port.BrokerEnabledPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// AccountService.register()/test()가 증권사 신규 계좌 등록 활성화 여부를 판단하려면 root
// admin_runtime_settings 상태가 필요하다 — root가 이 상태를 가지고 있어 root의
// RuntimeSettingsInternalController(GET /api/internal/runtime-settings/broker-enabled/{broker})를
// 호출해 위임한다(포트 역전의 제3의 형태 — 정의자(sharedkernel)도 데이터 소유자(admin)도 아닌
// trading-core가 HTTP로 구현). :trading-core→:api 역방향 Gradle 의존 금지 때문에 이 방향이 유일한 선택지
@Component
@RequiredArgsConstructor
class BrokerEnabledHttpAdapter implements BrokerEnabledPort {

    private final RestClient internalApiRestClient;

    @Override
    public boolean enabled(Broker broker) {
        Boolean result = internalApiRestClient.get()
                .uri("/api/internal/runtime-settings/broker-enabled/{broker}", broker)
                .retrieve()
                .body(Boolean.class);
        return Boolean.TRUE.equals(result);
    }
}
