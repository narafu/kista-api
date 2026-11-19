package com.kista.api;

import com.kista.api.notify.adapter.in.redis.PushNotificationStreamConsumer;
import com.kista.shared.platform.redis.RedisStreamSubscriber;
import com.kista.shared.platform.security.TokenBlacklistPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarRefreshPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.shared.platform.web.ExceptionReporter;
import com.kista.shared.platform.web.ProblemDetailExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

    @Autowired
    private Environment env; // EPR 완료 행 삭제 설정 확인용

    // role=all 기동 잡(캘린더 초기 적재·배치 재개)이 실제 Alpaca·api.telegram.org를 부르지 않게 외부 호출 포트만 막는다
    @MockitoBean
    private MarketCalendarRefreshPort marketCalendarRefreshPort; // Alpaca 캘린더 조회
    @MockitoBean
    private TradingErrorReportPort tradingErrorReportPort; // 배치 재개 실패 관리자 텔레그램 알림

    @Test
    void 애플리케이션_컨텍스트가_모든_빈을_생성자_주입까지_포함해_정상_로드한다() {
        // 컨텍스트 로드 도중 어떤 빈이라도 생성자 주입을 해석하지 못하면(예: @Autowired 누락으로
        // 인한 생성자 중의성) SpringBootTest가 BeanCreationException으로 여기 도달하기 전에 실패한다.
        // TossRedisTokenStore(package-private, adapter.out.toss)를 포함한 모든 빈이 이 시점에
        // 이미 생성자 주입까지 마쳤으므로, 빈 정의가 하나 이상 존재한다는 단언만으로
        // "정상 부팅" 검증은 충분하다.
        assertThat(context.getBeanDefinitionNames()).isNotEmpty();
    }

    @Test
    void 합친_컨텍스트는_JPA_리포지토리_전부와_프로세스당_하나인_빈을_하나씩_갖는다() {
        // 리포지토리 스캔 범위 회귀 가드 — root(com.kista.api)와 매매 엔진(com.kista.tradingcore) 리포지토리가 전부 잡혀야 한다
        assertThat(context.getBeanNamesForType(JpaRepository.class)).hasSize(JPA_REPOSITORIES);
        // shared 자동구성 순서 회귀 가드 — Boot 기본 필터 체인이 함께 등록되면 2개가 된다
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
        // 예외 advice·오류 보고는 애플리케이션당 하나 — 보고 빈이 없으면 advice가 조용히 보고를 건너뛴다
        assertThat(context.getBeanNamesForType(ProblemDetailExceptionHandler.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ExceptionReporter.class)).hasSize(1);
        // JwtAuthFilter가 요구하는 블랙리스트 포트도 하나 — platform 조건부 빈과 user 어댑터가 겹치지 않는다
        assertThat(context.getBeanNamesForType(TokenBlacklistPort.class)).hasSize(1);
    }

    @Test
    void 스트림_구독자는_푸시_알림_컨슈머_하나뿐이다() {
        // 프로세스 경계용 user.*·app.error 스트림 경로는 합병으로 지웠다 — 남은 구독자는 체결 푸시(FCM 위임) 하나
        assertThat(context.getBeansOfType(RedisStreamSubscriber.class).values())
                .singleElement().isInstanceOf(PushNotificationStreamConsumer.class);
    }

    @Test
    void 스케줄러는_가상_스레드_SimpleAsyncTaskScheduler다() {
        // 매매 배치 예약(TradingCloseScheduler)과 주기 잡이 한 TaskScheduler를 나눠 쓴다 — 가상 스레드라 서로 막지 않는다
        assertThat(context.getBean(TaskScheduler.class)).isInstanceOf(SimpleAsyncTaskScheduler.class);
    }

    @Test
    void EPR_완료_행은_삭제_모드다() {
        assertThat(env.getProperty("spring.modulith.events.completion-mode")).isEqualTo("delete");
    }

    private static final int JPA_REPOSITORIES = 41; // 실측값 — root 21 + 매매 엔진 20
}
