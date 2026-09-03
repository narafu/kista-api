// admin 모듈의 공개 계약 일부 — 불변 값 객체(record/enum). 관리자 read-model(AdminUserView/AdminStats 등),
// 감사·오류 로그 도메인(AuditLog/AppErrorLog), 런타임 설정(RuntimeSettings 트리). "domain" 이름으로 공개된다.
// AdminUserView.status/role의 User.UserStatus/UserRole 참조와 RuntimeSettings의 Strategy/Account nested enum
// 참조는 user/strategy-config/account가 아직 레거시라 forward — 각 모듈 CLOSED 전환(step 3/4) 시 정리 예정.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.admin.domain.model;
