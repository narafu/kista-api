// privacy 모듈의 공개 계약 일부 — 인바운드 UseCase 인터페이스(PrivacyUseCase는 FidaOrderController가, PrivacyTradeValidationUseCase는
// trading의 TradingOpenScheduler가 소비). "usecase" 이름으로 공개.
@org.springframework.modulith.NamedInterface("usecase")
package com.kista.privacy.application.usecase;
