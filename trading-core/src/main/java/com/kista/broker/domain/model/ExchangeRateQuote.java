package com.kista.broker.domain.model;

import java.math.BigDecimal;

// 벤더 중립 USD/KRW 환율 시세
public record ExchangeRateQuote(
    BigDecimal rate,    // 매수 환율 (1 USD 기준 KRW)
    BigDecimal midRate  // 매매기준율
) {}
