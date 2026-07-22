package com.kista;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 인시던트 재발 방지용 스모크 테스트: TossRedisTokenStore가 생성자 2개(@Autowired 누락)로
// Spring 부팅 시 BeanCreationException을 던져 Fly.io 두 머신이 모두 크래시 루프에 빠졌었다.
// 기존 Toss 관련 테스트는 전부 new로 직접 생성해 Spring DI 해석을 우회하므로,
// 이 테스트가 "모든 @Component/@Service/@Repository 빈이 생성자 주입까지 포함해
// 정상 인스턴스화되는지"를 검증하는 유일한 안전망이다.
// 의도적으로 @Tag("integration") 미부여: fly-deploy.yml verify job은 `./gradlew test`만 실행하고
// integration 태그는 excludeTags 대상이라, 태그를 붙이면 배포 게이트를 통과하지 못해 이 테스트의
// 존재 의의(배포 전 부팅 실패를 잡는 것)가 사라진다.
@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB 연결 컨텍스트 로드 — 병렬 실행 시 트랜잭션 충돌 방지 관례
class ApplicationContextLoadTest {

    @Autowired
    private ApplicationContext context; // 컨텍스트 로드 성공 여부 자체가 검증 대상

    @Test
    void 애플리케이션_컨텍스트가_모든_빈을_생성자_주입까지_포함해_정상_로드한다() {
        // 컨텍스트 로드 도중 어떤 빈이라도 생성자 주입을 해석하지 못하면(예: @Autowired 누락으로
        // 인한 생성자 중의성) SpringBootTest가 BeanCreationException으로 여기 도달하기 전에 실패한다.
        // TossRedisTokenStore(package-private, adapter.out.toss)를 포함한 모든 빈이 이 시점에
        // 이미 생성자 주입까지 마쳤으므로, 빈 정의가 하나 이상 존재한다는 단언만으로
        // "정상 부팅" 검증은 충분하다.
        assertThat(context.getBeanDefinitionNames()).isNotEmpty();
    }
}
