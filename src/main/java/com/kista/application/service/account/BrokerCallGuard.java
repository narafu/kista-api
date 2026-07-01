package com.kista.application.service.account;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

// 브로커 API 호출을 감싸 예외를 IllegalStateException으로 변환 — 단일 책임 예외 래핑 헬퍼
@Slf4j
final class BrokerCallGuard {

    private BrokerCallGuard() {}

    // label: 로그 메시지에 표시할 작업 설명 (예: "예수금 조회")
    static <T> T wrap(String label, Supplier<T> call) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("[{}] 증권사 API 조회에 실패했습니다: {}", label, e.getMessage(), e);
            throw new IllegalStateException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
    }
}
