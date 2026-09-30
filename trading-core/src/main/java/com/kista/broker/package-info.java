// broker 애그리게이트(KIS/Toss/Mock 증권사 API 연동) 모듈 — "domain"(domain.model)·"port"(application.port.output)·"application"(application.service) 3개 NamedInterface 공개, adapter와 domain.model.kis/toss(벤더 전용 모델)는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.broker;
