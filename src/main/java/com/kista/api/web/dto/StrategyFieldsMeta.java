package com.kista.api.web.dto;

import com.kista.shared.kernel.StrategyCreationSettings;
import com.kista.shared.kernel.StrategyFieldSettings;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

// 전략 생성 필드별 허용값·기본값 — RuntimeConfigService strategies[type].fields와 같은 모양. 적용 안 되는 필드는 null
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrategyFieldsMeta(
        @Schema(description = "종목 생성 설정 (customizable/allowedValues/defaultValue)")
        @Nullable StrategyFieldSettings<?> ticker,
        @Schema(description = "무한매수 분할 수 생성 설정")
        @Nullable StrategyFieldSettings<?> divisionCount,
        @Schema(description = "VR 정기 입출금 방향 생성 설정")
        @Nullable StrategyFieldSettings<?> recurringMode,
        @Schema(description = "VR 밴드 폭 생성 설정 (%)")
        @Nullable StrategyFieldSettings<?> bandWidth,
        @Schema(description = "VR 롤오버 주기 생성 설정 (주 단위)")
        @Nullable StrategyFieldSettings<?> intervalWeeks
) {
    public static StrategyFieldsMeta from(StrategyCreationSettings s) {
        return new StrategyFieldsMeta(s.ticker(), s.divisionCount(), s.recurringMode(), s.bandWidth(), s.intervalWeeks());
    }
}
