package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.TradingPolicyPort;
import com.kista.admin.domain.model.TradingPolicyUnavailableException;
import com.kista.platform.internalapi.InternalApiStatusHandlers;
import com.kista.sharedkernel.TradingPolicySettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// trading-core TradingPolicyInternalController(GET|PUT /api/internal/trading/policy-settings) 호출 —
// 매매 런타임 정책의 소유자는 trading-core라 admin은 읽고 쓰기를 위임만 한다. TradingPolicySettings는
// sharedkernel 어휘라 own-type 없이 그대로 역직렬화한다.
// 배포 전환기 호환: server-deploy.yml은 kista-api를 즉시, kista-trading은 매매 시간대 가드 뒤에 배포하므로 root가 먼저
// 새 버전이 되는 창이 있다. 그동안 옛 trading-core는 이 엔드포인트가 없어 404를 돌려주는데, 공개 /api/runtime-config까지
// 500으로 무너지지 않도록 조회는 기본값(TradingPolicySettings.defaults() — 새 trading-core도 행이 없으면 같은 값)으로
// 내리고 경고만 남긴다. 교체(PUT)는 조용히 성공한 척할 수 없으므로 명확한 메시지로 거절한다.
@Slf4j
@Component
@RequiredArgsConstructor
class TradingPolicyHttpAdapter implements TradingPolicyPort {

    private final RestClient internalApiRestClient;

    @Override
    public TradingPolicySettings load() {
        try {
            TradingPolicySettings settings = internalApiRestClient.get()
                    .uri("/api/internal/trading/policy-settings")
                    .retrieve()
                    .body(TradingPolicySettings.class);
            if (settings == null) throw new IllegalStateException("매매 런타임 정책 응답이 비어 있습니다");
            return settings;
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("trading-core가 아직 정책 API를 제공하지 않아 기본 정책으로 응답 — kista-trading 배포 전환기 (404)");
            return TradingPolicySettings.defaults();
        } catch (RestClientException e) {
            // 연결 거부·타임아웃·5xx — 전송 예외를 admin 어휘로 바꿔 application 계층이 spring-web에 의존하지 않게 한다
            throw new TradingPolicyUnavailableException(e.getMessage(), e);
        }
    }

    @Override
    public TradingPolicySettings replace(TradingPolicySettings settings) {
        // trading-core 쪽 생성자 검증 실패(enum 키 누락 등) 400은 IllegalArgumentException으로 되돌려 관리자에게 400으로 전달
        try {
            TradingPolicySettings saved = InternalApiStatusHandlers.badRequestAsIllegalArgument(
                    internalApiRestClient.put()
                            .uri("/api/internal/trading/policy-settings")
                            .body(settings)
                            .retrieve(), "매매 런타임 정책이 거절되었습니다")
                    .body(TradingPolicySettings.class);
            if (saved == null) throw new IllegalStateException("매매 런타임 정책 교체 응답이 비어 있습니다");
            return saved;
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalStateException("trading-core가 아직 정책 API를 제공하지 않습니다 — kista-trading 배포 후 다시 시도하세요");
        } catch (IllegalArgumentException e) {
            throw e; // 400 → badRequestAsIllegalArgument가 이미 변환 — 그대로 관리자에게 400
        } catch (RestClientException e) {
            throw new TradingPolicyUnavailableException(e.getMessage(), e);
        }
    }
}
