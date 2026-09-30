package com.kista.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Hexagonal Architecture 규칙")
class HexagonalArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().importPackages("com.kista");
    }

    @Test
    @DisplayName("sharedkernel은 다른 com.kista 모듈에 의존하지 않는다 — 전역 공용 어휘 불변식")
    void sharedkernel_must_not_depend_on_other_modules() {
        // sharedkernel은 outbound reference 0인 순수 값 타입만 담는다는 전제로 OPEN 선언됨 —
        // 이 패키지가 다른 모듈을 참조하는 순간 여러 모듈이 공유하는 어휘로서의 전제가 깨진다.
        // 그동안 package-info 주석에만 있던 불변식을 실제로 강제한다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.sharedkernel..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.kista..")
                                .and(resideOutsideOfPackage("com.kista.sharedkernel..")));
        rule.check(classes);
    }

    @Test
    @DisplayName("contract는 sharedkernel 외 다른 com.kista 모듈에 의존하지 않는다 — Published Language 불변식")
    void contract_must_not_depend_on_other_modules() {
        // contract는 프로세스 경계를 넘는 wire 스키마(순수 record)만 담는다 — 도메인 타입을 import하는 순간
        // trading-core가 root를(또는 그 반대를) 컴파일 타임에 알게 된다. sharedkernel(공용 어휘) 참조만 허용하고,
        // 자기 자신(com.kista.contract..)과 JDK·Jackson·Swagger·Bean Validation 어노테이션 외 의존은
        // InternalApiContractTest.contract_must_only_depend_on_allowed_packages가 별도로 잠근다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.contract..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.kista..")
                                .and(resideOutsideOfPackage("com.kista.contract.."))
                                .and(resideOutsideOfPackage("com.kista.sharedkernel..")));
        rule.check(classes);
    }

    @Test
    @DisplayName("platform은 다른 com.kista 모듈에 의존하지 않는다 — 인프라 leaf 불변식")
    void platform_must_not_depend_on_other_modules() {
        // platform은 persistence base·crypto·스케쥴러 골격 등 순수 인프라만 담는다는 전제로 OPEN 선언됨 —
        // 이 패키지가 다른 애그리게이트 모듈을 참조하는 순간 인프라 leaf 전제가 깨진다 (sharedkernel과 동일 강제).
        // com.kista.common 소멸(모듈 경계 재구성 #3)로 예외 절도 함께 제거 — 이제 sharedkernel과 완전히 동일한 outbound-zero.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.platform..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.kista..")
                                .and(resideOutsideOfPackage("com.kista.platform..")));
        rule.check(classes);
    }

    @Test
    @DisplayName("UsTradeDates는 4개 KIS/Toss/캘린더 어댑터에서만 사용한다 — 시간 기준 정책 allowlist 강제")
    void usTradeDates_must_only_be_used_by_allowlisted_adapters() {
        // constraints.md "시간 기준 정책" allowlist를 실제로 강제 — US 거래일 변환은 이 4개 어댑터 내부
        // 전용, 도메인·서비스·orders persistence에서 사용 금지. 클래스 단위 allowlist(메서드 단위 아님) —
        // TossPriceApi는 getClosingPrice 메서드만 실사용하지만 메서드 단위 강제는 ArchUnit 복잡도
        // 대비 이득이 낮음. ClassFileImporter가 테스트 클래스도 import하므로(DoNotIncludeTests 미지정),
        // 향후 어댑터 테스트가 기대값 계산에 UsTradeDates를 직접 쓰면 이 규칙이 함께 걸린다 —
        // 그때는 allowlist에 추가할지 검토할 것.
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName("com.kista.platform.time.UsTradeDates")
                .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.kis.KisTradingApi")
                .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.kis.KisPriceApi")
                .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.toss.TossPriceApi")
                .and().doNotHaveFullyQualifiedName("com.kista.marketcalendar.adapter.out.persistence.MarketCalendarPersistenceAdapter")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.kista.platform.time.UsTradeDates");
        rule.check(classes);
    }

    @Test
    @DisplayName("matching 커널은 sharedkernel 외 다른 com.kista 모듈에 의존하지 않는다")
    void matching_must_not_depend_on_other_modules() {
        // matching은 주문생성 순수 계산 커널만 담는다 — outbound는 sharedkernel(공용 어휘)뿐이다.
        // PRIVACY 기준 매매표는 커널 소유 PrivacyPlan으로 받고, 변환은 privacy(PrivacyTradeBase.toPlan())가 맡는다.
        // 허용 목록 방식이라 신규 모듈(contract/tradingstats/tradingnotify 등)이 생겨도 자동으로 차단된다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.matching..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.kista..")
                                .and(resideOutsideOfPackage("com.kista.matching.."))
                                .and(resideOutsideOfPackage("com.kista.sharedkernel..")));
        rule.check(classes);
    }

    @Test
    @DisplayName("인바운드 어댑터(adapter.in·web)는 org.springframework.web.client(RestClient 등)에 의존하지 않는다")
    void inbound_adapters_must_not_perform_outbound_http() {
        // 컨트롤러가 직접 다른 프로세스를 호출하면 포트 없이 아웃바운드 I/O를 하게 되고, 호출 대상 장애가
        // 공개 엔드포인트 장애로 전파된다 — 외부 호출은 adapter.out의 *HttpAdapter(포트 구현)만 맡는다.
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.kista..adapter.in..", "com.kista.web..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web.client..");
        rule.check(classes);
    }

    @Test
    @DisplayName("notify는 순수 아웃바운드 게이트웨이 — 다른 모듈 유스케이스와 web.client(RestClient)에 의존하지 않는다")
    void notify_must_stay_pure_outbound_gateway() {
        // 텔레그램 봇 명령 채널(승인/거절·조회)은 admin.adapter.in.telegram으로 이전됐다. notify가 유스케이스를 호출하거나
        // 다른 프로세스를 HTTP로 조회하는 순간 알림 게이트웨이가 다시 인바운드 명령 채널이 된다 —
        // 사용자 조회는 UserPort·이벤트로, 텔레그램 전송·getMe는 platform.telegram.TelegramHttpClient가 맡는다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.notify..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.kista..application.usecase..", "org.springframework.web.client..");
        rule.check(classes);
    }

    @Test
    @DisplayName("broker 밖에서는 벤더 전용 도메인 모델(domain.model.kis/toss)을 참조하지 않는다 — 벤더 타입 침투 차단")
    void vendor_models_must_not_leak_outside_broker() {
        // KIS/Toss 전용 타입(KisApiException/TossCandle 등)은 broker 어댑터 전용이다 — 소비자는 벤더 중립 타입
        // (BrokerApiException/BrokerCandle 등)만 안다. 신규 브로커 추가가 "구현체 1개 추가"로 끝나도록 잠근다.
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.kista.broker..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.kista.broker.domain.model.kis..", "com.kista.broker.domain.model.toss..");
        rule.check(classes);
    }

    @Test
    @DisplayName("web·tradingweb(앱셸)은 순수 inbound sink — application.service/adapter.out에 의존하지 않는다")
    void web_must_stay_pure_inbound_sink() {
        // com.kista.web·com.kista.tradingweb은 패키지에 adapter/application/domain 세그먼트가 없어
        // 위 도메인/application.service 규칙 와일드카드에 안 걸린다(미검사 표면) — 이 규칙으로 직접 강제한다.
        // tradingweb은 root web과 대칭인 trading-core 앱셸이라 같은 규칙을 적용한다.
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.kista.web..", "com.kista.tradingweb..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.kista..application.service..",
                        "com.kista..adapter.out.."
                );
        rule.check(classes);
    }

    @Test
    @DisplayName("@Aspect는 저장소에 두지 않는다 — 포인트컷 문자열은 모듈 간 숨은 런타임 결합을 만든다")
    void no_aspects_in_codebase() {
        // 포인트컷 문자열은 Modulith(ApplicationModules.verify)·ArchUnit이 보지 못하는 모듈 간 런타임 결합을 만든다
        // (옛 admin ErrorLogAspect가 notify.NotifyPort.notifyError를 문자열로 가로채던 사례) — 횡단 관심사는 이벤트 발행으로 푼다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..")
                .should().beAnnotatedWith("org.aspectj.lang.annotation.Aspect");
        rule.check(classes);
    }

    @Test
    @DisplayName("trading application.service·BatchContext는 Account 애그리게이트를 들고 다니지 않는다 — TradingAccount 투영만 사용")
    void trading_batch_path_must_not_carry_account_aggregate() {
        // BatchContext가 복호화된 자격증명을 담은 Account 전체를 들고 배치·프리뷰·리포트 경로를 흘러다니던 결합(리뷰 F8)을 잠근다.
        // 이 경로는 id/userId/nickname/brokerRef 4필드만 쓰므로 trading 소유 투영 TradingAccount만 받는다.
        // TradingAccount.from()은 정의 1곳이며 호출 경계는 3곳(BatchContextFactory / ManualTradingService / TradingPreviewService)이다.
        // 타입 표면을 좁히는 것이지 자격증명 은닉이 아니다 — brokerRef는 여전히 자격증명을 담고 toString만 마스킹한다.
        // 대상은 application.service 전체 + BatchContext(denylist 방식 — 신규·개명 클래스가 조용히 검사에서 빠지지 않는다).
        // BatchContextFactory는 adapter.in.schedule이라 대상 밖이다.
        // 아래 제외 목록은 Account를 실제로 참조하는 소유권 검증·계좌 조회 경로 클래스만 남긴다(실측으로 확인).
        // ManualTradingService/TradingPreviewService는 requireOwnedAccount 결과를 곧바로 TradingAccount.from()에 넘겨
        // Account 타입을 선언·보관하지 않으므로 제외 대상이 아니다 — 나중에 Account를 직접 다루게 되면 이 규칙이 잡는다.
        Set<String> accountAllowed = Set.of(
                "com.kista.trading.application.service.support.SelectionChain",      // 소유권 검증(verifyOwnedBy)
                "com.kista.trading.application.service.ReorderService",              // 소유권 검증
                "com.kista.trading.application.service.OrderCancelService",           // 소유권 검증
                "com.kista.trading.application.service.VrReconfigureService",        // 소유권 검증
                "com.kista.trading.application.service.StrategyCreationService",     // 계좌 소유권·브로커 검증
                "com.kista.trading.application.service.StrategyService",             // 계좌 소유권 검증
                "com.kista.trading.application.service.support.StrategyHistoryQueryService", // 소유권 검증
                "com.kista.trading.application.service.ManualTradeCorrectionService" // 소유권 검증
        );
        // 제외 목록 오타·개명 감지 — 이름이 실제 클래스로 존재해야 한다
        Set<String> existing = new java.util.HashSet<>();
        classes.forEach(c -> existing.add(c.getName()));
        assertThat(existing).containsAll(accountAllowed);
        // 중첩 클래스(TradingService$X 등)는 바깥 클래스 기준으로 제외 판정
        DescribedPredicate<JavaClass> isBatchPath = DescribedPredicate.describe(
                "trading 배치 경로 클래스(application.service 전체 + BatchContext, 제외 목록 제외)",
                c -> (c.getPackageName().startsWith("com.kista.trading.application.service")
                        || c.getName().equals("com.kista.trading.domain.model.BatchContext"))
                        && !accountAllowed.contains(c.getName().split("\\$")[0]));
        ArchRule rule = noClasses()
                .that(isBatchPath)
                .should().dependOnClassesThat().haveFullyQualifiedName("com.kista.account.domain.model.Account");
        rule.check(classes);
    }

    @Test
    @DisplayName("도메인은 어떤 외부 레이어도 의존하지 않는다")
    void domain_must_not_depend_on_outer_layers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.kista..application..",
                        "com.kista..adapter..",
                        "org.springframework.stereotype..",
                        "jakarta.persistence.."
                );
        rule.check(classes);
    }

    @Test
    @DisplayName("인바운드 어댑터는 application.service(구현체)에 직접 의존하지 않는다")
    void inbound_adapters_must_not_depend_on_application_layer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..adapter.in..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista..application.service..");
        rule.check(classes);
    }

    @Test
    @DisplayName("application 레이어는 adapter 레이어를 의존하지 않는다")
    void application_must_not_depend_on_adapter() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista..adapter..");
        rule.check(classes);
    }

    @Test
    @DisplayName("Service 클래스는 @Service 어노테이션을 가져야 한다")
    void service_classes_must_be_annotated_with_service() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista..application.service..")
                .and().haveSimpleNameEndingWith("Service")
                .should().beAnnotatedWith(org.springframework.stereotype.Service.class);
        rule.check(classes);
    }

    @Test
    @DisplayName("아웃바운드 포트 인터페이스는 *Port 접미사를 가져야 한다")
    void outbound_port_interfaces_must_have_Port_suffix() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista..application.port.output..")
                .and().areInterfaces()
                // package-info.class는 ACC_INTERFACE 플래그로 컴파일되어 areInterfaces()에 오탐 매칭됨 — 제외
                .and().doNotHaveSimpleName("package-info")
                .should().haveSimpleNameEndingWith("Port");
        rule.check(classes);
    }

    @Test
    @DisplayName("인바운드 포트 인터페이스는 *UseCase 또는 *Query 접미사를 가져야 한다")
    void inbound_port_interfaces_must_have_UseCase_or_Query_suffix() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista..application.usecase..")
                .and().areInterfaces()
                // package-info.class는 ACC_INTERFACE 플래그로 컴파일되어 areInterfaces()에 오탐 매칭됨 — 제외
                .and().doNotHaveSimpleName("package-info")
                .should().haveSimpleNameEndingWith("UseCase")
                .orShould().haveSimpleNameEndingWith("Query");
        rule.check(classes);
    }

    @Test
    @DisplayName("persistence JpaRepository는 *JpaRepository 접미사를 가져야 한다")
    void persistence_jpa_repositories_must_have_JpaRepository_suffix() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista..adapter.out.persistence..")
                .and().areInterfaces()
                .and().areAssignableTo(org.springframework.data.jpa.repository.JpaRepository.class)
                .should().haveSimpleNameEndingWith("JpaRepository");
        rule.check(classes);
    }

    @Test
    @DisplayName("persistence JpaRepository는 package-private이어야 한다")
    void persistence_jpa_repositories_must_be_package_private() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista..adapter.out.persistence..")
                .and().areInterfaces()
                .and().haveSimpleNameEndingWith("JpaRepository")
                .should().bePackagePrivate();
        rule.check(classes);
    }

    @Test
    @DisplayName("application.service는 org.springframework.web에 의존하지 않는다")
    void application_service_must_not_depend_on_spring_web() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..application.service..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web..");
        rule.check(classes);
    }

    @Test
    @DisplayName("application.service는 org.springframework.http.HttpStatus에 의존하지 않는다")
    void application_service_must_not_depend_on_http_status() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..application.service..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.http.HttpStatus");
        rule.check(classes);
    }

    @Test
    @DisplayName("KIS 파서는 application 레이어를 의존하지 않는다 — 정규화는 outbound 책임")
    void kis_parser_must_not_depend_on_application_layer() {
        // adapter.out은 domain.model 사용이 정상 — application 서비스 직접 의존만 금지
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..adapter.out.kis..")
                .and().haveSimpleNameEndingWith("Parser")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista..application..");
        rule.check(classes);
    }

    @Test
    @DisplayName("SSE EmitterRegistry는 adapter.in (controller)에서만 주입된다 — application 직접 의존 금지")
    void sse_emitter_registry_must_not_be_used_in_application_layer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista..adapter.out.sse..");
        rule.check(classes);
    }

    @Test
    @DisplayName("RestController는 application.usecase/application.port.output 인터페이스에만 의존하고 application 구현체에 직접 의존하지 않는다")
    void rest_controllers_must_not_depend_on_application_implementations() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..adapter.in.web..")
                .and().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista..application.service..");
        rule.check(classes);
    }

    @Test
    @DisplayName("생성자가 2개 이상인 Spring 빈은 정확히 하나에 @Autowired가 있어야 한다")
    void multi_constructor_beans_must_have_exactly_one_autowired_constructor() {
        // 실 인시던트 재발 방지: TossRedisTokenStore가 생성자 2개(테스트용 Clock 주입 오버로드 포함)인데
        // @Autowired가 없어 Spring이 기본 생성자를 못 찾고 BeanCreationException으로 부팅 자체가 실패한 사례
        ArchRule rule = classes()
                .that().areAnnotatedWith(org.springframework.stereotype.Component.class)
                .or().areAnnotatedWith(org.springframework.stereotype.Service.class)
                .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
                .should(haveExactlyOneAutowiredConstructorWhenMultiple());
        rule.check(classes);
    }

    private static ArchCondition<JavaClass> haveExactlyOneAutowiredConstructorWhenMultiple() {
        return new ArchCondition<>("생성자가 2개 이상이면 정확히 하나에 @Autowired가 있어야 함") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Set<JavaConstructor> constructors = javaClass.getConstructors();
                if (constructors.size() < 2) {
                    return;
                }
                long autowiredCount = constructors.stream()
                        .filter(constructor -> constructor.isAnnotatedWith(Autowired.class))
                        .count();
                if (autowiredCount != 1) {
                    events.add(SimpleConditionEvent.violated(javaClass, String.format(
                            "%s has %d constructors but %d are annotated with @Autowired (expected exactly 1)",
                            javaClass.getFullName(), constructors.size(), autowiredCount)));
                }
            }
        };
    }
}
