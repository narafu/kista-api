package com.kista.domain.model;

import java.math.BigDecimal;

/**
 * A=avgPrice Q=soxlQty M=A*Q D=effectiveAmt B=D+M K=B/20
 * T=floor(M/K) S=(20-T*2)/100 P=A*1.2 G=currentPrice
 */
public record TradingVariables(
        BigDecimal a,
        int q,
        BigDecimal m,
        BigDecimal d,
        BigDecimal b,
        BigDecimal k,
        int t,
        BigDecimal s,
        BigDecimal p,
        BigDecimal currentPrice
) {}
