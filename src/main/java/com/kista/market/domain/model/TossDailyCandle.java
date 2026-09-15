package com.kista.market.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// broker.domain.model.toss.TossCandle own-type 복제(일봉 1건) — market↔broker 순환 방지 목적.
// 필드 shape은 TossCandle과 byte-identical(내부API JSON 응답을 이 타입으로 바로 역직렬화)
public record TossDailyCandle(
    LocalDate date,     // 기준일
    BigDecimal open,    // 시가
    BigDecimal high,    // 고가
    BigDecimal low,     // 저가
    BigDecimal close,   // 종가
    long volume         // 거래량
) {}
