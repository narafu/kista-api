package com.kista.trading.adapter.in.redis;

import com.kista.sharedkernel.UserDeletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 리뷰 지적 회귀 방지 테스트 — UserEventStreamBridge.handleUserDeletedRecord()가 같은 클래스 안의
// @Transactional 메서드를 this.로 직접 호출(self-invocation)하면 Spring CGLIB 프록시를 완전히
// 우회해 @Transactional이 무력화된다. 이 경우 AFTER_COMMIT phase 리스너 중 fallbackExecution이
// 없는 것들(UserCascadeListener/StrategyUserCascadeListener)은 활성 트랜잭션이 없으면 예외·로그
// 없이 조용히 스킵되고, publishEvent()/ack() 자체는 정상 리턴하므로 메시지가 그대로 XACK돼
// 완전히 무증상으로 유실된다.
//
// 고친 방법: @Transactional 메서드를 UserEventStreamBridge와 분리된 별도 빈 UserEventRepublisher로
// 옮겨, 항상 다른 빈(프록시)을 거쳐 호출되도록 했다. 이 테스트는 실제 Spring 트랜잭션 프록시를
// 통해 호출했을 때만 (1) 호출 시점에 실제 트랜잭션이 열려 있고 (2) fallbackExecution 없는
// AFTER_COMMIT 리스너가 실제로 발화하는지를 검증한다 — 순수 Mockito 목으로는 프록시 자체가
// 없어 이 버그를 잡지 못한다(기존 UserEventStreamBridgeIT의 사각지대).
@DisplayName("UserEventRepublisher — self-invocation 없이 실제 트랜잭션 프록시를 통해 호출되는지 검증")
class UserEventRepublisherTransactionTest {

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {

        // 실제 DB 연결 없이도 AbstractPlatformTransactionManager의 트랜잭션 동기화(afterCommit
        // 콜백 트리거 포함) 골격만 그대로 재현하는 테스트 전용 트랜잭션 매니저 — doBegin/doCommit이
        // no-op이어도 isActualTransactionActive()·@TransactionalEventListener(AFTER_COMMIT) 발화는
        // 실제 트랜잭션 매니저와 동일하게 동작한다(둘 다 AbstractPlatformTransactionManager가 처리).
        static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean
        UserEventRepublisher userEventRepublisher(ApplicationEventPublisher eventPublisher) {
            return new UserEventRepublisher(eventPublisher);
        }

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    // fallbackExecution 없는 UserCascadeListener/StrategyUserCascadeListener 계열을 대표하는
    // 테스트 전용 리스너 — 트랜잭션이 없으면 조용히 스킵되는 동일 조건을 재현한다.
    static class Probe {
        volatile boolean transactionActiveDuringPublish;
        volatile boolean firedAfterCommit;

        @EventListener
        void onEventPublished(UserDeletedEvent event) {
            transactionActiveDuringPublish = TransactionSynchronizationManager.isActualTransactionActive();
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void onAfterCommit(UserDeletedEvent event) {
            firedAfterCommit = true;
        }
    }

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("프록시 빈을 거쳐 호출하면 실제 트랜잭션이 열리고 AFTER_COMMIT 리스너가 발화한다")
    void proxiedCall_opensRealTransaction_andFiresAfterCommitListener() {
        context = new AnnotationConfigApplicationContext(TxConfig.class);
        UserEventRepublisher proxiedRepublisher = context.getBean(UserEventRepublisher.class);
        Probe probe = context.getBean(Probe.class);

        proxiedRepublisher.republishUserDeleted(new UserDeletedEvent(UUID.randomUUID()));

        assertThat(probe.transactionActiveDuringPublish)
                .as("republish 호출 시점에 실제 트랜잭션이 열려 있어야 한다")
                .isTrue();
        assertThat(probe.firedAfterCommit)
                .as("fallbackExecution 없는 AFTER_COMMIT 리스너가 실제로 발화해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("회귀 재현 — 프록시 없이 원본 객체를 직접 호출하면(self-invocation과 동일 조건) AFTER_COMMIT 리스너가 조용히 스킵된다")
    void unproxiedCall_neverOpensTransaction_andSilentlySkipsAfterCommitListener() {
        context = new AnnotationConfigApplicationContext(TxConfig.class);
        Probe probe = context.getBean(Probe.class);
        // 스프링 컨테이너를 거치지 않고 new로 직접 생성 — this.republishXxx() self-invocation과
        // 동일하게 트랜잭션 프록시(AOP 어드바이스)를 완전히 우회하는 상황을 재현한다.
        // ApplicationEventPublisher는 컨텍스트 자체(멀티캐스터로 위임)를 그대로 재사용한다.
        UserEventRepublisher rawRepublisher = new UserEventRepublisher(context);

        rawRepublisher.republishUserDeleted(new UserDeletedEvent(UUID.randomUUID()));

        assertThat(probe.transactionActiveDuringPublish)
                .as("프록시를 거치지 않으면 트랜잭션이 열리지 않는다")
                .isFalse();
        assertThat(probe.firedAfterCommit)
                .as("트랜잭션이 없으면 fallbackExecution 없는 리스너는 예외 없이 조용히 스킵된다 — 이게 바로 원래 버그였다")
                .isFalse();
    }
}
