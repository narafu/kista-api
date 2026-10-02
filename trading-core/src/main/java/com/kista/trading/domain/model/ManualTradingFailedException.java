package com.kista.trading.domain.model;

// 바로주문 중 증권사 타입이 아닌 예상 밖 실패(계획 계산·예산 배정 중 DB/데이터 결함 등) — 500, 사용자에겐 고정 문구.
// 보고는 ManualTradingService가 TradingErrorEvent로 이미 수행하므로 핸들러 전용 매핑으로 처리해 catch-all 재보고를 피한다
public class ManualTradingFailedException extends ManualTradingException {
    public ManualTradingFailedException(Throwable cause) {
        super("바로주문 처리 중 예상 밖 오류: " + cause.getMessage(), cause);
    }
}
