// user(+auth) 애그리게이트(가입·승인·프로필·설정 + JWT/RefreshToken/블랙리스트/카카오 OAuth) 모듈 —
// domain.{model,auth}·application.{usecase,port.output,event}만 공개 계약,
// application.service·adapter·config는 internal.
// User nested enum 3종(UserRole/UserStatus/NotificationChannel) + NotificationType은
// com.kista.sharedkernel로 이관됨 — 이 모듈은 sharedkernel을 소비할 뿐 소유하지 않는다.
@org.springframework.modulith.ApplicationModule
package com.kista.user;
