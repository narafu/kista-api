// user(+auth) 애그리게이트(가입·승인·프로필·설정 + JWT/RefreshToken/블랙리스트/카카오 OAuth) 모듈 —
// domain.{model,auth}·application.{usecase,port.output,event}만 공개 계약,
// application.service·adapter·config는 internal.
// 옛 User nested enum 중 UserRole/UserStatus + NotificationType은 여러 모듈이 공유해 com.kista.sharedkernel로 이관됨.
// NotificationChannel은 user 단독 소비라 domain.model로 되돌렸다(sharedkernel "공용 어휘" 자격 없음).
@org.springframework.modulith.ApplicationModule
package com.kista.user;
