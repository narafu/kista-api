// notify 애그리게이트(Telegram/FCM 알림 발송) 모듈 — application.port.output만 공개 계약, application/adapter는 internal. SSE·포트폴리오 조회의 wire 타입은 com.kista.contract(TradeEventMessage/PortfolioCurrentResponse/PortfolioOrderResponse)를 그대로 쓴다 — 얇은 게이트웨이 모듈.
@org.springframework.modulith.ApplicationModule
package com.kista.notify;
