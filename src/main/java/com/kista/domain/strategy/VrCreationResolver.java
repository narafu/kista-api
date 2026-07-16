package com.kista.domain.strategy;

import com.kista.domain.model.settings.RecurringMode;
import com.kista.domain.model.settings.StrategyCreationSettings;
import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class VrCreationResolver implements StrategyCreationResolver {

    @Override
    public Strategy.Type type() {
        return Strategy.Type.VR;
    }

    @Override
    public ResolvedCreation resolveTypeFields(RegisterStrategyCommand cmd, StrategyCreationSettings settings, Strategy.Ticker ticker) {
        int recurringAmount = resolveRecurringAmount(cmd, settings);
        BigDecimal bandWidth = settings.bandWidth().resolve(cmd.bandWidth());
        Integer intervalWeeks = settings.intervalWeeks().resolve(cmd.intervalWeeks());
        return new ResolvedCreation(ticker, Strategy.DEFAULT_DIVISION_COUNT, intervalWeeks, bandWidth, recurringAmount);
    }

    // 방향(recurringMode)만 설정으로 검증하고 recurringAmount의 실제 크기는 기존 VR 자산 규칙(validateVrCommand)에 맡긴다.
    private int resolveRecurringAmount(RegisterStrategyCommand cmd, StrategyCreationSettings settings) {
        if (!settings.recurringMode().customizable()) {
            int amount = cmd.recurringAmount() != null ? cmd.recurringAmount() : 0;
            if (amount != 0) {
                throw new IllegalArgumentException("고정 recurringMode는 recurringAmount 0만 허용합니다");
            }
            return 0;
        }
        if (cmd.recurringAmount() == null) {
            // 생략 시 설정된 기본 방향을 적용한다 — HOLD(=0)만 크기 없이 default 적용 가능하고,
            // 그 외 방향은 크기를 알 수 없어 명시 입력을 요구한다 (defaultValue는 방향만 의미, 금액은 의미하지 않음).
            RecurringMode defaultMode = settings.recurringMode().resolve(null);
            if (defaultMode != RecurringMode.HOLD) {
                throw new IllegalArgumentException("recurringAmount는 필수입니다 (기본 방향: " + defaultMode + ")");
            }
            return 0;
        }
        settings.recurringMode().resolve(StrategyCreationResolver.recurringModeOf(cmd.recurringAmount()));
        return cmd.recurringAmount();
    }
}
