package com.kista.admin.adapter.out.aop;

import com.kista.admin.application.port.output.AppErrorLogPort;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

// NotifyPort.notifyError() 호출을 가로채 오류를 DB에 자동 저장한 뒤 원래 호출(텔레그램 발송)을 진행
// 포인트컷은 문자열 표현식이라 컴파일 의존은 없지만, admin→notify 의존 자체는 실재함 — 정적 분석(ArchUnit/
// ApplicationModules.verify())에 안 보이는 런타임 의존이다. notify 쪽에 admin으로의 엣지가 생기면
// verify()가 못 잡는 순환이 생길 수 있음(현재는 양방향 참조 0건 확인됨, admin package-info 참고)
@Aspect
@Component
@RequiredArgsConstructor
public class ErrorLogAspect {

    private final AppErrorLogPort appErrorLogPort;

    @Around("execution(* com.kista.notify.application.port.output.NotifyPort+.notifyError(..))")
    public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
        Exception e = (Exception) pjp.getArgs()[0];
        // 저장 실패 격리는 AppErrorLogPort.save() 계약(구현체 책임)으로 이동 — 여기서 별도 try/catch 불필요
        appErrorLogPort.save(e, pjp.getTarget().getClass().getName());
        return pjp.proceed();
    }
}
