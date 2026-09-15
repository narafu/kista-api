package com.kista.market.application.port.output;

import com.kista.market.domain.model.TossDailyCandle;

import java.util.List;

// broker 모듈 내부API(CandleInternalController) 호출 포트 — market이 broker.CandlePort/TossCandle을
// 직접 참조하지 않기 위함. 일봉만 지원 — interval은 어댑터 내부에서 "1d" 고정
public interface CandleQueryPort {
    List<TossDailyCandle> latestDailyCandles(String symbol, int count);
}
