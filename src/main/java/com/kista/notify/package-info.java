// notify 애그리게이트(Telegram/FCM 알림 발송) 모듈 — application.port.output만 공개 계약, application/adapter는 internal. domain 패키지 자체가 없는(모델 없음) 얇은 게이트웨이 모듈.
@org.springframework.modulith.ApplicationModule
package com.kista.notify;
