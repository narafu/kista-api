package com.kista.broker.domain.model;

// 주문 접수 결과 — BrokerOrderCorrectionPort.place() 응답. trading이 이 값으로 자신의 Order를 order.withPlaced(externalOrderId)로 갱신한다
public record OrderResult(String externalOrderId) {}
