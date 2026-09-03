package com.kista.architecture;

import com.kista.notify.application.port.output.NotifyPort;
import com.kista.trading.application.event.MarketClosedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// EPR이 리스너 annotation(@ApplicationModuleListener 아닌 기존 @TransactionalEventListener)과
// 무관하게 전역 추적되는지 실증 — 다음 태스크들의 annotation-미변경 결정의 전제 검증
@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class EventPublicationRegistryTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired IncompleteEventPublications incompleteEventPublications;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean NotifyPort notifyPort;

    @Test
    void 리스너_실패_시_incomplete_publication이_기록되고_재제출로_완료된다() {
        // TradingAlertNotifier.onMarketClosed(@TransactionalEventListener(fallbackExecution=true))가
        // MarketClosedEvent를 소비 — 이 리스너는 어떤 코드도 바꾸지 않은 기존 프로덕션 리스너다
        doThrow(new RuntimeException("강제 실패 1회차"))
                .doNothing()
                .when(notifyPort).notifyMarketClosed();

        try {
            eventPublisher.publishEvent(new MarketClosedEvent());
        } catch (RuntimeException ignored) {
            // fallbackExecution 동기 리스너 예외가 발행자까지 전파됨 — 이 테스트에서는 무시
        }

        // event_publication은 전역 테이블이라 다른 @SpringBootTest가 발행한 이벤트도 함께 쌓인다 —
        // event_type으로 이 테스트가 발행한 MarketClosedEvent만 좁혀서 집계(전체 COUNT는 병렬/타 테스트 오염에 취약)
        Long incompleteCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%MarketClosedEvent' AND completion_date IS NULL", Long.class);
        assertThat(incompleteCount).isEqualTo(1L);

        incompleteEventPublications.resubmitIncompletePublications(pub -> true);

        Long remainingIncomplete = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%MarketClosedEvent' AND completion_date IS NULL", Long.class);
        assertThat(remainingIncomplete).isEqualTo(0L);
        verify(notifyPort, times(2)).notifyMarketClosed(); // 최초 실패 1회 + 재제출 성공 1회
    }
}
