package com.kista.domain.port.out;

// clientId+clientSecret으로 OAuth 토큰 발급 → GET /api/v1/accounts → 첫 번째 accountSeq 반환
// 실패 시 Account.InvalidKisKeyException (GlobalExceptionHandler 422 매핑 공유)
public interface TossConnectionTestPort {
    String testAndFetchAccountSeq(String clientId, String clientSecret);
}
