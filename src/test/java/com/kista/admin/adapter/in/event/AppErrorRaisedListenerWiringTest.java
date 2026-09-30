package com.kista.admin.adapter.in.event;

import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

// 실제 Spring 컨텍스트에서 이벤트 발행 → 리스너 → 포트 저장이 배선되는지 검증 (옛 ErrorLogAspectPointcutTest의 컨텍스트 검증 복원)
// 리스너 메서드가 package-private이라도 @EventListener가 컨텍스트에서 동작함을 증명한다
@SpringJUnitConfig(AppErrorRaisedListenerWiringTest.Config.class)
// 같은 Spring 컨텍스트의 appErrorLogPort mock을 공유 — 병렬 실행 시 서로 오염됨 (testing.md Mockito 병렬 테스트 주의사항)
@Execution(ExecutionMode.SAME_THREAD)
class AppErrorRaisedListenerWiringTest {

    @Configuration
    @Import(AppErrorRaisedListener.class) // package-private 리스너를 최소 컨텍스트에 빈으로 등록
    static class Config {

        @Bean
        AppErrorLogPort appErrorLogPort() {
            return mock(AppErrorLogPort.class);
        }
    }

    @Autowired ApplicationEventPublisher publisher;
    @Autowired AppErrorLogPort appErrorLogPort;

    @BeforeEach
    void resetMocks() {
        // 공유 컨텍스트의 mock 호출 기록이 다른 테스트에 남지 않도록 초기화
        reset(appErrorLogPort);
    }

    @Test
    void AppErrorRaisedEvent_발행_시_리스너가_호출되어_포트로_저장된다() {
        publisher.publishEvent(new AppErrorRaisedEvent("IllegalStateException", "boom", "at foo()", Map.of("caller", "GlobalExceptionHandler")));

        verify(appErrorLogPort).save("IllegalStateException", "boom", "at foo()", Map.of("caller", "GlobalExceptionHandler"));
    }
}
