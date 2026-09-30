// broker 모듈의 공개 계약 일부 — 벤더 중립 값 객체(Currency/DailyTransaction*/BrokerApiException/BrokerCandle 등). domain.model.kis/toss(벤더 전용 모델)는 공개하지 않는다(broker 어댑터 내부 전용). application.service는 별도 "application" 이름으로 공개.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.broker.domain.model;
