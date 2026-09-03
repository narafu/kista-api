package com.kista.strategyconfig.domain.model;

import java.math.BigDecimal;
import com.kista.sharedkernel.StrategyCycleSeedType;

// 전략 수정 인바운드 파라미터
public record UpdateStrategyCommand(
        StrategyCycleSeedType cycleSeedType, // null이면 기존 값 유지
        BigDecimal newSeed // null이면 시드 미변경, non-null이면 총자산 B 교체
) {}
