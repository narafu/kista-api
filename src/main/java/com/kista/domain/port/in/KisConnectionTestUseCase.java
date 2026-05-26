package com.kista.domain.port.in;

import java.util.UUID;

public interface KisConnectionTestUseCase {
    // accountId null 허용 — null이면 캐시 저장 생략 (계좌 등록 전 검증)
    boolean test(String appKey, String appSecret, UUID accountId);
}
