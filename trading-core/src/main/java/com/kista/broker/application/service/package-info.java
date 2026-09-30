// broker 모듈의 공개 계약 — BrokerConnectionTesters/BrokerStatisticsPorts/BrokerCallGuard (공통 Port 라우터 BrokerRouter는 package-private — 소비처는 Port를 직접 주입). "application" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("application")
package com.kista.broker.application.service;
