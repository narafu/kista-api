package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// DCA 회차 미주문 사유 — dca_execution.skip_reason 상수명과 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaSkipReason {
    DATA_UNAVAILABLE("데이터 확보 실패"),
    CONDITION_NOT_MET("조건 미충족"),
    NO_HOLDINGS("보유 수량 없음"),
    INSUFFICIENT_HOLDINGS("보유 수량 부족"), // 매도 REJECT 정책 — 가용 보유가 계획 수량보다 적음
    INSUFFICIENT_BALANCE("예수금 부족"),
    BELOW_ONE_SHARE("1주 미만 금액"),
    BELOW_MIN_ORDER_AMOUNT("최소 주문 금액 미달"), // 코인 — 산정 금액이 거래소 최소 주문 금액(5,000원) 미만
    EXCHANGE_API_ERROR("거래소 API 오류"),        // 코인 — 업비트 API 실패
    API_KEY_EXPIRED("API 키 인증 실패");          // 코인 — 인증 실패(키 만료·폐기·IP 미허용·권한 — 바이낸스 -2015는 구분 불가). 상수명은 DB·UI 계약이라 유지

    private final String label; // 한국어 표시 이름
}
