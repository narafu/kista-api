package com.kista.admin.adapter.out.aop;

import com.kista.admin.application.port.output.AppErrorLogPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

// NotifyPort.notifyError() 호출을 가로채 오류를 DB에 자동 저장한 뒤 원래 호출(텔레그램 발송)을 진행
// 포인트컷은 문자열 표현식이라 notify 모듈에 컴파일 의존 없음 — admin 자체 포트(AppErrorLogPort)만 소비
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ErrorLogAspect {

    private final AppErrorLogPort appErrorLogPort;

    @Around("execution(* com.kista.notify.application.port.output.NotifyPort+.notifyError(..))")
    public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
        Exception e = (Exception) pjp.getArgs()[0];
        // DB 저장 실패가 텔레그램 알림을 막지 않도록 격리
        try {
            appErrorLogPort.save(e, pjp.getTarget().getClass().getName());
        } catch (Exception saveEx) {
            log.warn("오류 로그 저장 실패: {}", saveEx.getMessage());
        }
        return pjp.proceed();
    }
}
