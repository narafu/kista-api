package com.kista.domain.port.out;

import java.util.UUID;

public interface KisConnectionTestPort {
    // accountId null 허용 — null이면 캐시 저장 생략 (등록 전 사전 검증)
    boolean test(String appKey, String appSecret, UUID accountId);
}
