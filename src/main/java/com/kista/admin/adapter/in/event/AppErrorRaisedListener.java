package com.kista.admin.adapter.in.event;

import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// root 프로세스 안에서 발행된 AppErrorRaisedEvent(GlobalExceptionHandler 500 · TelegramAdapter.notifyError)를
// app_error_logs에 저장하는 인바운드 이벤트 어댑터 — 옛 ErrorLogAspect(NotifyPort 포인트컷)를 대체한다.
// trading-core 발행분은 Redis Stream 경로(adapter/in/redis/AppErrorStreamConsumer)가 같은 포트로 저장한다.
// 동기 @EventListener인 이유: 발행 지점이 트랜잭션 밖인 경우가 많아 @TransactionalEventListener는 이벤트를 버린다.
// 저장 실패 격리는 AppErrorLogPort.save() 계약(구현체 책임)이라 여기서 별도 try/catch가 필요 없다.
@Component
@RequiredArgsConstructor
class AppErrorRaisedListener {

    private final AppErrorLogPort appErrorLogPort; // 오류 로그 저장 포트

    @EventListener
    void on(AppErrorRaisedEvent event) {
        appErrorLogPort.save(event.errorType(), event.message(), event.stackTrace(), event.context());
    }
}
