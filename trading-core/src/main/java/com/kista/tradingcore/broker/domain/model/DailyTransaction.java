package com.kista.tradingcore.broker.domain.model;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;

public record DailyTransaction(
        String tradeDate,            // 매매일자 (KST)
        OrderDirection direction,    // 매도/매수 방향
        StrategyTicker ticker,       // 종목코드
        String symbolName,           // 종목명
        BigDecimal quantity,         // 체결수량 — 주식은 정수 표기, 코인은 소수
        BigDecimal price,            // 체결단가 (계좌 통화)
        BigDecimal tradeAmount,      // 거래금액 (계좌 통화, 통화 자릿수로 반올림)
        Currency currency            // 계좌 통화 (KIS=USD, BINANCE=USDT, UPBIT=KRW)
) {}
