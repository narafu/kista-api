package com.kista.tradingcore.broker.application.service;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Supplier;

// 브로커 API 호출 예외 래핑 헬퍼 — 증권사 타입 예외(503 장애/422 자격증명/429 호출한도)는 원인별 상태코드를
// 보존하도록 그대로 전파하고, 그 외 예상 밖 예외만 사용자용 메시지의 IllegalStateException으로 변환한다
@Slf4j
public final class BrokerCallGuard {

    private BrokerCallGuard() {}

    // 증권사 타입 예외 여부 — 원인별 상태코드(503/422/429)를 보존해야 하는 예외는 래핑하지 않는다
    public static boolean isBrokerTyped(Throwable e) {
        return e instanceof BrokerApiException || e instanceof BrokerCredentialException || e instanceof BrokerRateLimitException;
    }

    // label: 로그 메시지에 표시할 작업 설명 (예: "전일종가 조회")
    public static <T> T wrap(String label, Supplier<T> call) {
        try {
            return call.get();
        } catch (Exception e) {
            if (isBrokerTyped(e)) {
                log.warn("[{}] 증권사 API 조회에 실패했습니다: {}", label, e.getMessage());
                throw (RuntimeException) e;
            }
            log.warn("[{}] 증권사 API 조회에 실패했습니다: {}", label, e.getMessage(), e);
            throw new IllegalStateException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
    }

    // wrap의 fail-open 짝 — 실패하면 중단하지 않고 로그만 남긴 뒤 empty를 돌려줘 호출부가 대체값으로 건너뛰게 한다
    // (wrap은 fail-closed: 실패 시 예외 전파). 이벤트 발행·분기 처리가 필요한 실패 정책은 직접 catch한다
    // 스택 없이 메시지만 남긴다(반복 호출 경로의 로그 폭주 방지)
    public static <T> Optional<T> tryGet(String label, Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RuntimeException e) {
            log.warn("[{}] 증권사·시세 조회에 실패해 건너뜁니다: {}", label, e.toString());
            return Optional.empty();
        }
    }
}
