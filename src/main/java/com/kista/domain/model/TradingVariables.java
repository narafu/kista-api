package com.kista.domain.model;

import java.math.BigDecimal;

/**
 * A=avgPrice Q=soxlQty M=A*Q D=effectiveAmt B=D+M K=B/20
 * T=floor(M/K) S=(20-T*2)/100 P=A*1.2 G=currentPrice
 */
public record TradingVariables(
        BigDecimal a,           // 기준 매입가 (A): 수량>0이면 avgPrice, 수량==0이면 currentPrice
        int q,                  // SOXL 보유 수량 (Q)
        BigDecimal m,           // 보유 자산액 (M) = A × Q
        BigDecimal d,           // 유효 자산액 (D): 유가증권 평가액
        BigDecimal b,           // 총 자산액 (B) = D + M
        BigDecimal k,           // 슬롯 단위 크기 (K) = B ÷ 20
        int t,                  // 현재 층수 (T) = floor(M ÷ K), 익절 가능 슬롯 수
        BigDecimal s,           // 매수 비중 (S) = (20 - T×2) ÷ 100, 층 높을수록 감소
        BigDecimal p,           // 익절 목표가 (P) = A × 1.2
        BigDecimal currentPrice // 현재 시장가 (G)
) {}
