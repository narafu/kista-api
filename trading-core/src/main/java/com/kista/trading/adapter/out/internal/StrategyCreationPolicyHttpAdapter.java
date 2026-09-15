package com.kista.trading.adapter.out.internal;

import com.kista.sharedkernel.StrategyCreationSettings;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.port.StrategyCreationPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

// StrategyCreationService.register()가 전략 타입별 생성 정책을 판단하려면 root admin_runtime_settings
// 상태가 필요하다 — root가 이 상태를 가지고 있어 root의 RuntimeSettingsInternalController
// (GET /api/internal/runtime-settings/strategy-creation-policy/{type})를 호출해 위임한다(BrokerEnabledHttpAdapter와
// 동일 패턴). 정책 미설정 시 root가 204를 반환하며, 이는 정상적인 "정책 없음" 응답이라 예외로 취급하지 않는다.
// .exchange()는 retrieve()와 달리 기본 상태 코드 핸들러가 적용되지 않아 4xx/5xx를 직접 걸러야 한다 —
// 걸러내지 않으면 root 장애(500)·내부 토큰 오류(401) 응답까지 "정책 없음"/"정책 조회 성공"으로
// 오판정될 위험이 있다(리뷰에서 실측 확인)
@Component
@RequiredArgsConstructor
class StrategyCreationPolicyHttpAdapter implements StrategyCreationPolicyPort {

    private final RestClient internalApiRestClient;

    @Override
    public Optional<StrategyCreationSettings> find(StrategyType type) {
        return Optional.ofNullable(internalApiRestClient.get()
                .uri("/api/internal/runtime-settings/strategy-creation-policy/{type}", type)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 204) return null;
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("전략 생성 정책 조회 실패: " + response.getStatusCode());
                    }
                    return response.bodyTo(StrategyCreationSettings.class);
                }));
    }
}
