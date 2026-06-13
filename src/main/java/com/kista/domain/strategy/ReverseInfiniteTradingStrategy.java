package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.ReverseModePosition;

import java.time.LocalDate;
import java.util.List;

// 리버스모드(소진 후) 전략 인터페이스 — InfiniteTradingStrategy 패턴과 동일 구조
// 구현체: ReverseInfiniteStrategy (package-private)
public interface ReverseInfiniteTradingStrategy {

    // 소진 직후 첫날: MOC 매도만 생성
    List<Order> buildFirstDayOrders(ReverseModePosition position, LocalDate tradeDate);

    // 두번째 날 이후: LOC 매도(별지점 위) + LOC 쿼터매수(별지점 아래)
    List<Order> buildOrders(ReverseModePosition position, LocalDate tradeDate);
}
