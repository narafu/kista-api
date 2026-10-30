package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.tradingcore.privacy.domain.model.PrivacyCurrentBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 전략 등록 시작금액(startAmount) 산출 — PRIVACY 중간부터 시작만 FIDA 기준 배수로 환산
@Slf4j
@Component
@RequiredArgsConstructor
class StrategyStartAmountCalculator {

    private final PrivacyTradePort privacyTradePort; // PRIVACY 중간부터 시작 — FIDA 기준값으로 시작금액 산출

    // startAmount = 현금 + 시장가×보유수량(DCA는 평단×보유 = 초기 투입 원가) — VR도 총 시작자산을 동일하게 보존한다(vrValue override와 무관)
    // PRIVACY 중간부터 시작만 FIDA 기준 배수로 환산한다 — 매매표 배수(startAmount ÷ currentCycleStart)가 보유 비율과 맞도록
    // deposit은 호출측이 null→0 처리한 값
    BigDecimal calculate(StrategyType type, BigDecimal deposit, BigDecimal initialHoldings, BigDecimal initialStockValue) {
        return type == StrategyType.PRIVACY && initialHoldings.signum() > 0
                ? privacyBootstrapStartAmount(deposit, initialHoldings)
                : deposit.add(initialStockValue);
    }

    // PRIVACY 중간부터 시작 시작금액 — 원가 기준 자산 비율로 배수를 구해 FIDA 사이클 시작금액에 곱한다
    // 배수 = (예수금 + 보유×FIDA 평단) ÷ (FIDA 사이클 시작금액 + 실현손익), 소수 2자리 버림(PrivacyStrategy 배수 산출과 동일)
    // 예: 3,445.25 + 417×125.95 = 55,966.40 ÷ (14,434.58 − 454.07) = 4.00배 → 57,738.32
    private BigDecimal privacyBootstrapStartAmount(BigDecimal initialUsdDeposit, BigDecimal initialHoldings) {
        PrivacyCurrentBase base = privacyTradePort.findSeedPreviewBase()
                .orElseThrow(() -> new IllegalArgumentException("PRIVACY 기준 매매표가 없어 중간부터 시작할 수 없습니다."));
        BigDecimal fidaBookValue = base.currentCycleStart().add(base.currentCycleRealizedPnl());
        if (base.avgPrice() == null || fidaBookValue.signum() <= 0) {
            throw new IllegalArgumentException("PRIVACY 기준 매매표에 보유 평단이 없어 중간부터 시작할 수 없습니다.");
        }
        BigDecimal userBookValue = initialUsdDeposit.add(initialHoldings.multiply(base.avgPrice()));
        BigDecimal multiple = userBookValue.divide(fidaBookValue, 2, RoundingMode.FLOOR);
        log.info("[PRIVACY] 중간부터 시작 배수: userBookValue={}, fidaBookValue={}, multiple={}", userBookValue, fidaBookValue, multiple);
        if (multiple.signum() <= 0) {
            throw new IllegalArgumentException("보유 자산이 PRIVACY 기준 매매표 대비 너무 적어 중간부터 시작할 수 없습니다.");
        }
        // CEILING — 매매 시 startAmount ÷ currentCycleStart를 FLOOR로 되돌리므로 반올림 내림이면 배수가 0.01 낮아진다
        return multiple.multiply(base.currentCycleStart()).setScale(2, RoundingMode.CEILING);
    }
}
