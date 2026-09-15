package com.kista.stats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// trading.stats.domain.model.InvestmentPoint own-type — InvestmentPointsPort가 root 소유
// 타입만 쓰도록 컴파일 의존을 없애기 위한 복제(값 shape 동일)
public record InvestmentPoint(
        LocalDate baseDate,
        BigDecimal investmentIndexUsd,
        BigDecimal periodReturn
) {
}
