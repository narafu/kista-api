package com.kista.broker.adapter.out.internal;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;

// KIS/Toss가 공유하는 "확정종가 조회 → 실패 시 현재가 폴백" 제어흐름 — 각 브로커의 실제 조회 함수는
// 함수형 인자로 주입받는다. BrokerPricePort default 메서드로 두지 않는 이유는 ClosingPriceLoop와 동일:
// Mockito mock이 default 메서드를 override해 단건 조회만 stub한 기존 테스트에서 연결이 끊긴다.
public final class ConfirmedCloseFallback {

    private ConfirmedCloseFallback() {
    }

    // confirmedCloseLookup: 브로커별 확정종가 조회(검증·예외처리를 자체 포함, 실패 시 Optional.empty())
    // livePriceLookup: 확정종가 조회 실패 시 대체할 라이브 현재가 조회
    public static BigDecimal resolve(Supplier<Optional<BigDecimal>> confirmedCloseLookup,
                                      Supplier<BigDecimal> livePriceLookup) {
        return confirmedCloseLookup.get().orElseGet(livePriceLookup);
    }
}
