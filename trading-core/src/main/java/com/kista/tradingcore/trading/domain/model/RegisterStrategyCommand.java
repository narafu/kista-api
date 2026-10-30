package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.tradingcore.trading.domain.creation.VrRampInput;

// 전략 등록 인바운드 파라미터
public record RegisterStrategyCommand(
        StrategyType type,
        StrategyTicker ticker,                      // null이면 전략 기본값
        BigDecimal initialUsdDeposit,                // null 허용 (선택 입력), VR에서는 예수금(초기 pool)으로 재사용
        StrategyCycleSeedType cycleSeedType,        // null이면 NONE으로 처리
        int divisionCount,                           // 분할 수 (20/30/40), 0은 미입력 sentinel로 런타임 기본값 적용
        // 중간부터 시작 — 기존 보유 수량·평단가 (세 전략 공통, null/0이면 빈 포지션에서 시작)
        BigDecimal initialHoldings,                   // 등록 시점 기존 보유 수량 (주식 정수, 코인 소수 8자리까지)
        BigDecimal initialAvgPrice,                   // 등록 시점 기존 평단가 (initialHoldings>0이면 필수)
        // VR 전략 전용 필드 (비VR 경로는 null)
        Integer intervalWeeks,                       // 리밸런싱 주기 (주 단위, 1 이상, VR 전용)
        BigDecimal bandWidth,                         // 매수·매도 사다리 밴드 폭 (%, VR 전용)
        Integer recurringAmount,                      // 주기당 추가 예수금 (USD, 음수=인출, VR 전용)
        VrRampInput vrRamp,                           // VR 램프 8파라미터 입력 (미지정 필드는 서비스에서 기본값 적용, null이면 NONE, VR 전용)
        LocalDate scheduledStartDate,                  // 시작예정일 (null이면 오늘, 과거 거부)
        // VR: 초기 V값 직접 지정 (VR 전용, null/0 이하면 미지정 취급 — 평가금 기준으로 대체)
        BigDecimal initialVrValue,
        DcaStrategyConfig dca                          // DCA 전용 규칙 설정 (비DCA는 null)
) {
    public RegisterStrategyCommand {
        if (vrRamp == null) {
            vrRamp = VrRampInput.NONE; // 램프 전부 미지정
        }
    }
}
