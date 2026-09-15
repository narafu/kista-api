// marketcalendar 애그리게이트(미국 시장 휴장일 캘린더) 모듈 — domain.model·application.port.output만 공개 계약, application.service·adapter는 internal.
// trading의 유일한 market 계열 의존이 이 모듈로 좁혀진다("port" NamedInterface).
@org.springframework.modulith.ApplicationModule
package com.kista.marketcalendar;
