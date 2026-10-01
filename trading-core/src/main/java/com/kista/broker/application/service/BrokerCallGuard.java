package com.kista.broker.application.service;

import com.kista.broker.domain.model.BrokerApiException;
import com.kista.broker.domain.model.BrokerCredentialException;
import com.kista.broker.domain.model.BrokerRateLimitException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

// 브로커 API 호출 예외 래핑 헬퍼 — 증권사 타입 예외(503 장애/422 자격증명/429 호출한도)는 원인별 상태코드를
// 보존하도록 그대로 전파하고, 그 외 예상 밖 예외만 사용자용 메시지의 IllegalStateException으로 변환한다
@Slf4j
public final class BrokerCallGuard {

    private BrokerCallGuard() {}

    // label: 로그 메시지에 표시할 작업 설명 (예: "전일종가 조회")
    public static <T> T wrap(String label, Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerApiException | BrokerCredentialException | BrokerRateLimitException e) {
            log.warn("[{}] 증권사 API 조회에 실패했습니다: {}", label, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("[{}] 증권사 API 조회에 실패했습니다: {}", label, e.getMessage(), e);
            throw new IllegalStateException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
    }
}
