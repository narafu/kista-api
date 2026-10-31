package com.kista.tradingcore.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

class StreamSubscriberStartupTest {

    @Test
    @DisplayName("RedisStreamSubscriber 하위 클래스는 @PostConstruct로 구독을 시작하지 않는다 — ApplicationReadyEvent 사용")
    void stream_subscribers_must_not_start_in_post_construct() {
        // 메서드 리스너 등록 전에 밀린 메시지를 publishEvent하면 이벤트가 유실되고 Modulith 리스너 캐시가 비어 EPR 기록이 멈춘다
        JavaClasses classes = new ClassFileImporter().importPackages("com.kista");
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat().areAssignableTo("com.kista.shared.platform.redis.RedisStreamSubscriber")
                .should().beAnnotatedWith("jakarta.annotation.PostConstruct");
        rule.check(classes);
    }
}
