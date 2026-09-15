// notify 애그리게이트(Telegram/FCM 알림 발송) 모듈 — application.port.output만 공개 계약, application/adapter는 internal. domain/model엔 SSE 전용 값 객체(TradeEventView, 대응하는 trading-core 원본 타입 없음)만 있고 별도 NamedInterface는 없음(모듈 내부에서만 소비하는 포트 시그니처 타입) — 얇은 게이트웨이 모듈.
@org.springframework.modulith.ApplicationModule
package com.kista.notify;
