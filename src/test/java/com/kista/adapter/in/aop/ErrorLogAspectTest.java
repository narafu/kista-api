package com.kista.adapter.in.aop;

import com.kista.domain.port.out.AppErrorLogPort;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ErrorLogAspectTest {

    @Mock AppErrorLogPort appErrorLogPort;
    @Mock ProceedingJoinPoint pjp;
    ErrorLogAspect aspect;

    RuntimeException testException = new RuntimeException("테스트 오류");

    @BeforeEach
    void setUp() {
        aspect = new ErrorLogAspect(appErrorLogPort);
        when(pjp.getArgs()).thenReturn(new Object[]{testException});
        when(pjp.getTarget()).thenReturn(new Object());
    }

    @Test
    void intercept_saves_to_db_and_proceeds() throws Throwable {
        // Aspect가 DB 저장 후 원래 호출(텔레그램 발송)도 실행하는지 확인
        aspect.intercept(pjp);

        verify(appErrorLogPort).save(eq(testException), eq("java.lang.Object"));
        verify(pjp).proceed();
    }

    @Test
    void intercept_proceeds_even_when_save_throws() throws Throwable {
        // DB 저장 실패해도 텔레그램 발송은 반드시 실행
        doThrow(new RuntimeException("DB 저장 실패")).when(appErrorLogPort).save(any(), anyString());

        assertThatCode(() -> aspect.intercept(pjp)).doesNotThrowAnyException();
        verify(pjp).proceed();
    }
}
