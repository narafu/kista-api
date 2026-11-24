package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.StrategyType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 전략 행위 엔드포인트 코드값 — 응답 allowedActions·blockedActions(메타 strategyActions). 주문 일괄 취소는 행위 판정 대상이 아니라 제외
@Getter
@RequiredArgsConstructor
public enum StrategyAction {
    PAUSE("일시중지"),                 // PATCH /{id}/pause
    RESUME("재개"),                    // PATCH /{id}/resume
    ARCHIVE("보관"),                   // PATCH /{id}/archive
    DELETE("삭제"),                    // DELETE /{id}
    EDIT_SEED("시작 예수금 수정"),      // PUT /{id} newSeed
    INJECT_CAPITAL("투자금 추가"),      // POST /{id}/capital-injection
    WITHDRAW_CAPITAL("투자금 인출"),    // POST /{id}/capital-withdrawal
    RESET_VR("VR 재설정"),             // PUT /{id}/vr-config
    EDIT_DCA_CONFIG("DCA 설정 수정");   // PUT /{id}/dca-config

    private final String label; // 한국어 표시 이름 (메타 번들)

    // 전략 종류별 지원 여부 SSOT — 서비스의 종류 거절(투자금 추가·인출 등)과 응답 판정이 같이 쓴다
    public boolean supports(StrategyType type) {
        return switch (this) {
            case PAUSE, RESUME, ARCHIVE, DELETE -> true;
            case EDIT_SEED -> type == StrategyType.INFINITE || type == StrategyType.PRIVACY; // VR은 재설정, DCA는 투자금 추가·인출로만
            case INJECT_CAPITAL, WITHDRAW_CAPITAL -> type == StrategyType.INFINITE || type == StrategyType.PRIVACY || type == StrategyType.DCA;
            case RESET_VR -> type == StrategyType.VR;
            case EDIT_DCA_CONFIG -> type == StrategyType.DCA;
        };
    }

    // 지원하지만 지금 상태로는 막힌 사유 — 메타 strategyActionBlockReasons
    @Getter
    @RequiredArgsConstructor
    public enum BlockReason {
        ARCHIVED("보관된 전략"),                        // 보관은 한 방향 — 삭제 외 모든 행위 불가
        ALREADY_PAUSED("이미 일시중지됨"),              // 일시중지 중복
        ALREADY_ACTIVE("이미 운영 중"),                 // 재개 중복
        NO_ACTIVE_CYCLE("진행 중인 사이클 없음"),        // 사이클 없음·최신 사이클 종료
        HAS_HOLDINGS("보유 수량 있음"),                 // 보관·시드 수정은 보유 수량 0에서만
        NO_WITHDRAWABLE_BALANCE("인출 가능한 예수금 없음"); // 장부 예수금 0 이하

        private final String label; // 한국어 표시 이름 (메타 번들)
    }
}
