package com.kista.admin.adapter.in.redis;

import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreamSubscriber;
import com.kista.platform.redis.RedisStreams;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

// trading-core가 stream:app.error로 push한 AppErrorRaisedEvent를 app_error_logs에 저장하는 구독자 —
// 과거 ErrorLogInternalController(POST /api/internal/errors, trading-core→root 동기 HTTP)를 대체한다.
// root의 두 role(kista-api·kista-scheduler)이 같은 컨슈머 그룹으로 나눠 받는다(메시지당 1회 처리, 컨슈머 이름은
// 인스턴스마다 고유). 구독·ack·재기동·XCLAIM 복구 골격은 platform RedisStreamSubscriber가 맡고, 이 클래스는
// 봉투 payload → app_error_logs 저장(onRecord)과 기동·5분 주기 복구 배선만 가진다. 저장 실패 격리는
// AppErrorLogPort.save() 계약이 보장한다.
@Component
public class AppErrorStreamConsumer extends RedisStreamSubscriber {

    private final ObjectMapper objectMapper;
    private final AppErrorLogPort appErrorLogPort;

    public AppErrorStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper, AppErrorLogPort appErrorLogPort) {
        super(connectionFactory, redisTemplate, RedisStreamConfig.APP_ERROR_STREAM,
                RedisStreamConfig.ROOT_CONSUMER_GROUP, "root");
        this.objectMapper = objectMapper;
        this.appErrorLogPort = appErrorLogPort;
    }

    @PostConstruct
    void init() {
        start();
    }

    // 봉투 payload → AppErrorRaisedEvent → app_error_logs 저장. 역직렬화 실패는 예외로 던져 베이스가 warn 후 ack —
    // poison 메시지의 영구 재시도를 막는다
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        AppErrorRaisedEvent event = objectMapper.readValue(RedisStreams.payload(record), AppErrorRaisedEvent.class);
        appErrorLogPort.save(event.errorType(), event.message(), event.stackTrace(), event.context());
    }

    // 5분마다 끊긴 구독 재기동 + pending 복구 — SchedulerJobRunner를 쓰지 않는 이유는
    // STARTED/COMPLETED 알림(SchedulerNotifier)이 5분마다 텔레그램에 쌓이기 때문
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void scheduledRecovery() {
        recoverPending();
    }
}
