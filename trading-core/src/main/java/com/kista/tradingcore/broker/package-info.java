// broker 애그리게이트(Meritz/Toss/Mock 증권사 API 연동) 모듈 — "domain"(domain.model)·"port"(application.port.output)·"application"(application.service)·"marketdata"(공용 시세 AlpacaMarketDataAdapter 타입 하나) 4개 NamedInterface 공개, 그 밖의 adapter와 domain.model.toss(벤더 전용 모델)는 internal.
@org.springframework.modulith.ApplicationModule(id = "broker")
package com.kista.tradingcore.broker;
