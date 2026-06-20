package com.kista.domain.model.toss;

import java.math.BigDecimal;

// Toss 환율 정보 — GET /api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW
public record TossExchangeRate(
    BigDecimal rate,    // 매수 환율 (1 USD 기준 KRW)
    BigDecimal midRate  // 매매기준율
) {}
