package com.kista.trading.domain.model;

// credentialFailedCount: failedCount 중 증권사 자격증명 오류(키 만료·철회)로 실패한 건수 — 재시도해도 풀리지 않는다
public record CancelResult(int cancelledCount, int failedCount, int credentialFailedCount) {

    public CancelResult(int cancelledCount, int failedCount) {
        this(cancelledCount, failedCount, 0);
    }

    // 다시 시도하면 풀릴 수 있는(일시 장애) 실패 건수 — 삭제 차단 판단에 쓴다
    public int retryableFailedCount() {
        return failedCount - credentialFailedCount;
    }
}
