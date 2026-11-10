package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.tradingcore.privacy.domain.model.PrivacyCurrentBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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

    // PRIVACY 중간부터 시작 시작금액 — 공식은 PrivacyCurrentBase.bootstrapStartAmount(백테스트 공용)
    private BigDecimal privacyBootstrapStartAmount(BigDecimal initialDeposit, BigDecimal initialHoldings) {
        PrivacyCurrentBase base = privacyTradePort.findSeedPreviewBase()
                .orElseThrow(() -> new IllegalArgumentException("PRIVACY 기준 매매표가 없어 중간부터 시작할 수 없습니다."));
        BigDecimal startAmount = base.bootstrapStartAmount(initialDeposit, initialHoldings);
        log.info("[PRIVACY] 중간부터 시작 시작금액: deposit={}, holdings={}, startAmount={}", initialDeposit, initialHoldings, startAmount);
        return startAmount;
    }
}
