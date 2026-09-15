// trading 모듈의 공개 계약 일부 — 주문(Order 등)·사이클 실행 이력(StrategyCycle/CyclePosition 등) 불변 값 객체. domain.strategy와 함께 "domain" 이름으로 병합 공개된다. UseCase/Port는 별도 "usecase"/"port" 이름으로 공개.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.trading.domain.model;
