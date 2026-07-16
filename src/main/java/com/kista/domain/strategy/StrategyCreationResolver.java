package com.kista.domain.strategy;

import com.kista.domain.model.settings.RecurringMode;
import com.kista.domain.model.settings.StrategyCreationSettings;
import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;

// 전략 등록 시 런타임 설정(StrategyCreationSettings) 기반 필드 해석 진입점
// 각 구현체는 type()으로 자기 타입을 선언하며, 서비스는 Map<Strategy.Type, StrategyCreationResolver>로 주입받아 사용
public interface StrategyCreationResolver {

    // 이 리졸버가 담당하는 전략 타입
    Strategy.Type type();

    // ticker는 모든 전략 유형에서 생략 기본값·허용값·고정값 정책을 동일하게 적용한다.
    default ResolvedCreation resolve(RegisterStrategyCommand cmd, StrategyCreationSettings settings) {
        Strategy.Ticker ticker = settings.ticker().resolve(cmd.ticker());
        return resolveTypeFields(cmd, settings, ticker);
    }

    // 전략 타입별 고유 필드(division count / VR 파라미터 등) 해석
    ResolvedCreation resolveTypeFields(RegisterStrategyCommand cmd, StrategyCreationSettings settings, Strategy.Ticker ticker);

    // signed recurringAmount를 런타임 설정의 UI 방향 enum으로 변환 — VR 리졸버 공용
    static RecurringMode recurringModeOf(int recurringAmount) {
        if (recurringAmount > 0) return RecurringMode.DEPOSIT;
        if (recurringAmount < 0) return RecurringMode.WITHDRAW;
        return RecurringMode.HOLD;
    }

    // 런타임 생성 정책을 적용한 뒤 저장·검증에 전달하는 값 묶음
    record ResolvedCreation(Strategy.Ticker ticker, int divisionCount, Integer intervalWeeks,
                             BigDecimal bandWidth, Integer recurringAmount) {}
}
