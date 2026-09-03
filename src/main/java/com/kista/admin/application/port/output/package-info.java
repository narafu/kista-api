// admin 모듈의 공개 계약 일부 — *Port 접미사 출력 포트. "port" 이름으로 공개된다.
// AuditLogPort/AppErrorLogPort/AdminUserViewPort/RuntimeSettingsPort. AdminUserViewPort는 미래 user 모듈이,
// RuntimeSettingsPort는 미래 account/strategy-config/user가 구현·소비 — 현재는 레거시 forward.
@org.springframework.modulith.NamedInterface("port")
package com.kista.admin.application.port.output;
