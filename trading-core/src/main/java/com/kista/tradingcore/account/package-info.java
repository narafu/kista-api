// account 애그리게이트(계좌 자격증명·브로커 연결) 모듈 —
// domain.model·application.{port.output,event}와 AccountService("api")만 공개 계약, 나머지 application.service·adapter internal.
@org.springframework.modulith.ApplicationModule(id = "account")
package com.kista.tradingcore.account;
