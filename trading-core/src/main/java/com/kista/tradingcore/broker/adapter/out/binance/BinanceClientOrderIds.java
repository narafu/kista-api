package com.kista.tradingcore.broker.adapter.out.binance;

import java.util.UUID;

// 주문 UUID ↔ 바이낸스 clientOrderId(하이픈 제거 32자 hex) 변환 단일 지점
final class BinanceClientOrderIds {

    private BinanceClientOrderIds() {
    }

    // UUID 형식이 아니면 IAE — 주문 ID가 아닌 값의 전송 방지
    static String toWire(String uuid) {
        return UUID.fromString(uuid).toString().replace("-", "");
    }

    // 32자 hex면 8-4-4-4-12로 복원, 아니면(바이낸스 자동 생성 ID 등) 그대로
    static String fromWire(String wire) {
        if (wire == null || !wire.matches("[0-9a-fA-F]{32}")) return wire;
        return wire.substring(0, 8) + "-" + wire.substring(8, 12) + "-" + wire.substring(12, 16) + "-"
                + wire.substring(16, 20) + "-" + wire.substring(20);
    }
}
