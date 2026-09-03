package com.kista.trading.domain.strategy;

import com.kista.sharedkernel.StrategyDefaults;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Component
public class VrCreationResolver implements StrategyCreationResolver {

    @Override
    public StrategyType type() {
        return StrategyType.VR;
    }

    @Override
    public ResolvedCreation resolveTypeFields(StrategyCreationRequest request, StrategyCreationSettings settings, StrategyTicker ticker) {
        int recurringAmount = resolveRecurringAmount(request, settings);
        BigDecimal bandWidth = settings.bandWidth().resolve(request.bandWidth());
        Integer intervalWeeks = settings.intervalWeeks().resolve(request.intervalWeeks());
        return new ResolvedCreation(ticker, StrategyDefaults.DEFAULT_DIVISION_COUNT, intervalWeeks, bandWidth, recurringAmount);
    }

    // 방향(recurringMode)만 설정으로 검증하고 recurringAmount의 실제 크기는 기존 VR 자산 규칙(validateVrCommand)에 맡긴다.
    private int resolveRecurringAmount(StrategyCreationRequest request, StrategyCreationSettings settings) {
        if (!settings.recurringMode().customizable()) {
            int amount = request.recurringAmount() != null ? request.recurringAmount() : 0;
            if (amount != 0) {
                throw new IllegalArgumentException("고정 recurringMode는 recurringAmount 0만 허용합니다");
            }
            return 0;
        }
        if (request.recurringAmount() == null) {
            // 생략 시 설정된 기본 방향을 적용한다 — HOLD(=0)만 크기 없이 default 적용 가능하고,
            // 그 외 방향은 크기를 알 수 없어 명시 입력을 요구한다 (defaultValue는 방향만 의미, 금액은 의미하지 않음).
            RecurringMode defaultMode = settings.recurringMode().resolve(null);
            if (defaultMode != RecurringMode.HOLD) {
                throw new IllegalArgumentException("recurringAmount는 필수입니다 (기본 방향: " + defaultMode + ")");
            }
            return 0;
        }
        settings.recurringMode().resolve(StrategyCreationResolver.recurringModeOf(request.recurringAmount()));
        return request.recurringAmount();
    }
}
