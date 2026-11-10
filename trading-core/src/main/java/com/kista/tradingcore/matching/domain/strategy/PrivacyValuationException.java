package com.kista.tradingcore.matching.domain.strategy;

// PRIVACY 배수(자산비) 산출 불가 — 호출부가 매매 skip·미리보기 차단·백테스트 세션 skip으로 처리한다
public final class PrivacyValuationException extends IllegalStateException {
    public PrivacyValuationException(String message) {
        super(message);
    }
}
