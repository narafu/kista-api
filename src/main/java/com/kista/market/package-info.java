// market 애그리게이트(공포탐욕지수) 모듈 — domain.model·application.port.output·application.event만 공개 계약, application.service·adapter는 internal.
// 미국 시장 휴장일 캘린더는 com.kista.marketcalendar로 분리됨.
@org.springframework.modulith.ApplicationModule
package com.kista.market;
