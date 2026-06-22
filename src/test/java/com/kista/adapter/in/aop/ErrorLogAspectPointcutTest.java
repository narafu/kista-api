package com.kista.adapter.in.aop;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.AppErrorLogPort;
import com.kista.domain.port.out.NotifyPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Spring AOP + CGLIB 환경에서 포인트컷이 NotifyPort 구현 클래스에 실제로 적용되는지 검증
// proxyTargetClass=true: Spring Boot 기본값과 동일한 CGLIB 방식 — 이 설정에서 NotifyPort+(+없음)는 미매칭
@SpringJUnitConfig(ErrorLogAspectPointcutTest.Config.class)
class ErrorLogAspectPointcutTest {

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true) // Spring Boot 기본 CGLIB 프록시 재현
    static class Config {

        @Bean
        AppErrorLogPort appErrorLogPort() {
            return mock(AppErrorLogPort.class);
        }

        @Bean
        ErrorLogAspect errorLogAspect(AppErrorLogPort appErrorLogPort) {
            return new ErrorLogAspect(appErrorLogPort);
        }

        @Bean
        NotifyPort notifyPort() {
            return new NotifyPort() {
                @Override public void notifyMarketClosed() {}
                @Override public void notifyInsufficientBalance(Account account, AccountBalance b, Strategy.Ticker ticker) {}
                @Override public void notifyError(Exception e) {} // 실제 발송 없이 포인트컷 매칭만 검증
                @Override public void notifyInfo(String message) {}
            };
        }
    }

    @Autowired NotifyPort notifyPort;
    @Autowired AppErrorLogPort appErrorLogPort;

    @Test
    void notifyError_호출_시_포인트컷이_매칭되어_DB에_저장된다() {
        RuntimeException e = new RuntimeException("포인트컷 검증 오류");

        notifyPort.notifyError(e);

        // NotifyPort+.notifyError() 포인트컷이 CGLIB 구현 클래스에 매칭되어야 함
        verify(appErrorLogPort).save(eq(e), anyString());
    }

    @Test
    void save_실패해도_notifyError_예외_없이_완료된다() {
        doThrow(new RuntimeException("DB 저장 실패")).when(appErrorLogPort).save(any(), anyString());

        assertThatCode(() -> notifyPort.notifyError(new RuntimeException("test")))
                .doesNotThrowAnyException();
    }
}
