// admin 모듈의 공개 계약 일부 — *Port 접미사 출력 포트. "port" 이름으로 공개된다.
// AuditLogPort/AppErrorLogPort/RuntimeSettingsPort(root 소유 설정 영속화)/TradingPolicyPort(trading-core 소유 정책 위임 — HTTP 어댑터).
@org.springframework.modulith.NamedInterface("port")
package com.kista.admin.application.port.output;
