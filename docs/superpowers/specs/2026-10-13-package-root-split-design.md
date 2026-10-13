# Package root split (B) — 설계

- 날짜: 2026-10-13
- 브랜치: `refactor/package-root-split`
- 환경: Java 25, Gradle 9.8.1, Spring Boot 4.1.1, Spring Modulith 2.1.1

## 1. 목적

2026-09-15 `de693b5c`에서 trading-core를 별도 Gradle 서브프로젝트로 분리하면서 디렉토리만 옮기고 패키지는 그대로 뒀다. 그 결과 `:api`·`:trading-core`·`:shared` 세 서브프로젝트가 `com.kista.*` 루트를 공유하고, 이를 메우는 우회 코드가 쌓였다.

- `KistaApplication.scanBasePackages` 9개 목록
- `RootJpaScanConfig`의 `@EntityScan`/`@EnableJpaRepositories` 목록(앞 목록과 수동 동기화)
- `TradingApplication`의 `scanBasePackages`/`@EntityScan`/`@ConfigurationPropertiesScan` 목록
- `JpaRepositoryConfig`의 `@EnableJpaRepositories` 목록
- 루트 패키지가 없어 생긴 앱셸 `tradingweb`
- 테스트의 `@ContextConfiguration(classes = TradingApplication.class)` 22곳, `TradingCoreJpaTestConfig` 참조 17곳

목표는 서브프로젝트마다 고유 루트 패키지를 주고, 위 우회 코드를 Spring Boot 기본 동작(자기 루트 스캔 + 라이브러리 자동구성)으로 대체하는 것이다. 비용이 들더라도 이상적 구조를 택한다(사용자 결정).

**성공 기준**
- 앱 클래스에 패키지 목록이 하나도 없다(`scanBasePackages`·`@EntityScan`·`@EnableJpaRepositories`·`@ConfigurationPropertiesScan` 0개 — `@EnableJpaRepositories`는 Step 0 실측이 필요하다고 판정할 때만 루트에 1개, 패키지 목록 없이 허용).
- Modulith 검증이 프로세스별로 실제 모듈 전부를 커버한다(조용한 누락 없음).
- ArchUnit 규칙이 빈 대상으로 통과하지 못한다.
- 전체 `test`·`integration`(root 14개, trading-core 5개 실제 실행)·`docker build`·로컬 2-프로세스 부팅 통과.
- 운영 배포 시 EPR 고아 0건.

## 2. 사전 실측 결과 (2026-10-13)

| 항목 | 결과 | 계획 영향 |
|---|---|---|
| Modulith 모듈 감지 | 기본 `direct-sub-packages`, 루트 `com.kista` → 19개 모듈. 모듈 ID = 루트 기준 trailing name(`ApplicationModuleSource`) | 그냥 옮기면 3개 모듈로 뭉개지거나(`com.kista` 루트) trading-core·shared가 검증에서 조용히 빠진다(`com.kista.api` 루트). 프로세스별 모델로 재구성 |
| ArchUnit | `src/test/resources/archunit.properties`에 `archRule.failOnEmptyShould=false` | 낡은 패키지 문자열(Hexagonal 62곳, GradleBoundary 16곳, InternalApiContract 4곳, TradingCoreTestVendorModel 4곳)이 거짓 통과한다. `true`로 영구 전환 |
| Redis | 전부 `MapRecord<String,String,String>` + 일반 JSON(`ObjectMapper.writeValueAsString`), 문자열 값. `JsonTypeInfo`/default typing/`@Cacheable` 없음. 스트림·채널 키는 상수 문자열 | FQCN 무관 — 신·구 프로세스 혼재 안전 |
| EPR | `event_type`·`listener_id`에 FQCN 저장. `deploy/hooks/pre-apply.sh`+`epr-orphans.sh`가 고아를 fail-closed 차단 | 이번 변경 후 모든 미완료 row가 고아 → 배포 전 drain 필수 |
| 로깅 | `logging.level.com.kista` 접두사 | 변경 불필요 |
| Flyway/DB | Java 마이그레이션(`BaseJavaMigration`) 0개, 클래스명 기반 락 이름 없음 | 영향 없음 |
| springdoc | `use-fqn` 미설정 → 스키마 이름은 단순 클래스명 | kista-ui `gen:types` 영향 없음 |
| 빌드 스크립트 | `mainClass`/`Start-Class` 하드코딩 없음(`group = "com.kista"`만) | 변경 불필요 |
| 문자열 FQCN | `flows.yml` `code:` 6곳, `ApplicationModules.of(KistaApplication.class)` 2곳 | 문서 커밋에서 수정 |
| split package | `com.kista.support`가 root testFixtures·trading-core test·trading-core testFixtures 3곳 | 소유별 분리 |
| 모듈 `CLAUDE.md` | 19개, `@../../..` 상대 import | 디렉토리 깊이 +1 → 조용히 깨짐. 일괄 수정 |
| root 테스트의 trading-core 참조 | `DomainFixtures` 14, `DataJpaTestBase` 1, trading 스케쥴러·포트·`EquityCurveResponse` 등 소수 + 아키텍처 테스트 | 이번 범위에선 `testImplementation(":trading-core")` 유지 |

## 3. 결정 사항

| # | 결정 | 근거 |
|---|---|---|
| D1 | Modulith 모델을 프로세스별 2개로 분리(`ApiApplication`·`TradingApplication` 각각 `@Modulithic(additionalPackages = "com.kista.shared")`) | 두 프로세스는 같은 JVM에서 돌지 않는다. 통합 모델은 허구이며, 프로세스 간 의존은 Gradle(`GradleModuleBoundaryTest`)이 이미 막는다. 각 루트 기준 ID라 짧은 ID 유지·`web` 충돌 해소 |
| D2 | `:shared`는 Spring Boot 자동구성으로 자기 빈을 등록 | 라이브러리가 소비자의 컴포넌트 스캔에 기대지 않는 것이 Boot 관례. 앱 스캔 목록 0개 |
| D3 | 같은 커밋으로 3-role 일괄 컷오버, 단일 PR | Redis FQCN 무관이라 단계 배포 이득 없음. EPR drain 1회 |
| D4 | 매 커밋 컴파일 통과. 기계적 커밋 = `git mv` + 스크립트 치환, 수작업은 별도 커밋 | bisect 가능, package/import 줄만 바뀌어 rename 감지 유지 |
| D5 | `KistaApplication` → `com.kista.api.ApiApplication` | `TradingApplication`과 대칭 |
| D6 | `tradingweb` → `com.kista.tradingcore.web`(모듈 ID `web`), `TradingApplication`은 `com.kista.tradingcore` 루트 | `api.web`과 대칭 |
| D7 | `tradingstats` → `tradingcore.stats`, `tradingnotify` → `tradingcore.notify` | `trading` 접두사는 루트 부재 우회. EPR drain을 이번에 어차피 하므로 추가 비용 0, 이후 개명은 drain 재발생 |
| D8 | `sharedkernel` → `com.kista.shared.kernel` | 위와 동일 근거. `shared.sharedkernel` 중복 제거 |
| D9 | `failOnEmptyShould=true` 영구 전환 | 거짓 통과 구조적 차단 |
| D10 | 서브프로젝트 접두사 경계 규칙 신설 | 삭제하는 스캔 allowlist가 하던 "root 테스트 classpath의 trading-core 배제" 역할을 패키지 구조 + 규칙으로 대체 |

`tradingcore.trading`은 실제 애그리게이트 이름이라 유지한다. `api.notify`와 `tradingcore.notify`는 모델이 분리돼 충돌하지 않으며, 문서에서는 프로세스 접두 표기(`tradingcore.notify`)를 쓴다.

## 4. 패키지 맵

| 서브프로젝트 | 현재 | 이후 |
|---|---|---|
| `:shared` | `com.kista.sharedkernel` | `com.kista.shared.kernel` |
| | `com.kista.contract` | `com.kista.shared.contract` |
| | `com.kista.platform` | `com.kista.shared.platform` |
| `:trading-core` | `com.kista.{trading,matching,broker,account,privacy,marketcalendar}` | `com.kista.tradingcore.{동일}` |
| | `com.kista.tradingstats` | `com.kista.tradingcore.stats` |
| | `com.kista.tradingnotify` | `com.kista.tradingcore.notify` |
| | `com.kista.tradingweb` (`TradingApplication` 제외) | `com.kista.tradingcore.web` |
| | `com.kista.tradingweb.TradingApplication` | `com.kista.tradingcore.TradingApplication` |
| `:api` | `com.kista.{admin,finance,market,notify,benchmark,user,web}` | `com.kista.api.{동일}` |
| | `com.kista.KistaApplication` | `com.kista.api.ApiApplication` |
| 테스트 지원 | `com.kista.support.{DataJpaTestBase,WebMvcTestSupport}` + `application-test.yml` (trading-core testFixtures) | `:shared` testFixtures `com.kista.shared.support` |
| | `com.kista.support.TradingFixtures` (trading-core testFixtures) | `com.kista.tradingcore.support` |
| | `com.kista.support.{TradingCoreJpaTestConfig,StubBrokerApiException}` (trading-core test) | `TradingCoreJpaTestConfig` 삭제(5절), `StubBrokerApiException` → `com.kista.tradingcore.support` |
| | `com.kista.support.DomainFixtures` (root testFixtures) | `com.kista.api.support` |
| | `com.kista.architecture` (root test) | `com.kista.api.architecture` (교차 서브프로젝트 규칙 포함 — 6절) |
| | `com.kista.architecture` (trading-core test) | `com.kista.tradingcore.architecture` |
| | `com.kista.web.RootDataJpaTestBase` 등 root 테스트 | 소속 모듈 패키지를 따라 `com.kista.api.*` |

`:shared` testFixtures 이동 시 `java-test-fixtures` 플러그인과 의존(`testImplementation(testFixtures(project(":shared")))`)을 양쪽에 추가하고, `application-test.yml`의 trading 전용 설정이 있으면 trading-core test 리소스에 남긴다(계획 단계에서 파일 내용으로 판정).

## 5. 부팅·스캔 구성

### 앱 클래스
```java
// com.kista.api.ApiApplication / com.kista.tradingcore.TradingApplication 공통 형태
@SpringBootApplication
@Modulithic(additionalPackages = "com.kista.shared")
@EnableScheduling
```
- `@Modulithic.additionalPackages`는 Modulith 검증 모델 범위만 넓힌다. Spring 컴포넌트 스캔과 무관.
- `bootJar`는 `@SpringBootApplication` 단일 후보를 자동 감지하므로 빌드 스크립트 변경 없음.

### `:shared` 자동구성
- `com.kista.shared.platform.PlatformAutoConfiguration` 1개 + `shared/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- 현재 컴포넌트를 `@Import`로 명시: `SecurityConfig`, `JwtDecoderConfig`, `JwtAuthFilter`, `InternalTokenAuthFilter`, `RedisPubSubConfig`, `TelegramConfig`, `InternalApiClientConfig`, `MetricsConfig`, `AesCryptoService`, `AccountNoHasher`, `SchedulerJobRunner`, `SchedulerLockService`, `JpaAuditingConfig`. `TelegramProperties`·`InternalApiProperties`는 `@EnableConfigurationProperties`.
- 목록은 구현 시 `grep @Configuration|@Component|@Service|@ConfigurationProperties`로 재확정한다(위는 2026-10-13 기준 15개 파일).
- `@EnableJpaAuditing`이 슬라이스 테스트에서 깨지는 기존 제약(constraints.md "@EnableJpaAuditing 위치")이 자동구성 안에서도 유지되는지 Step 0 스파이크에서 확인한다.

### JPA
- 앱 클래스가 각 루트로 가면 `AutoConfigurationPackages`가 `com.kista.api`/`com.kista.tradingcore`가 되어 엔티티·리포지토리가 기본 스캔된다.
- `JpaRepositoryConfig` 주석은 원인을 Redis strict 모드로 적었으나, 실제 원인은 `TradingApplication`이 `tradingweb`에 있어 자동구성 패키지가 `tradingweb`이었던 것으로 추정된다. Step 0 스파이크에서 부팅 로그 "Found N JPA repository interfaces"를 이동 전후 비교해 확정한다.
  - 기본 동작으로 충분 → `RootJpaScanConfig`·`JpaRepositoryConfig`·`TradingCoreJpaTestConfig`·`@EntityScan` 삭제.
  - 부족 → 각 루트에 패키지 목록 없는 `@EnableJpaRepositories` 설정 클래스 1개만 두고 실측 근거를 주석으로 남긴다.
- `BaseAuditEntity`/`BaseCreatedAtEntity`는 `@MappedSuperclass`라 스캔 대상이 아니다.

### 테스트
- trading-core `@WebMvcTest`/`@DataJpaTest`는 `@SpringBootConfiguration` 상향 탐색으로 `TradingApplication`을 찾는다 → `@ContextConfiguration` 22곳·`TradingCoreJpaTestConfig` 17곳 삭제.
- **위험 W1 — `@WebMvcTest` 슬라이스**: 지금은 `JwtAuthFilter` 등 `Filter` 빈이 컴포넌트 스캔으로 슬라이스에 포함된다. 자동구성으로 옮기면 슬라이스에서 빠진다. 영향받는 테스트를 Step 0에서 목록화하고, 기존 `@Import({SecurityConfig.class, JwtAuthFilter.class, ...})` 패턴으로 통일한다(testing.md 패턴과 일치).
- **위험 W2 — `@DataJpaTest`가 앱 클래스 메타 어노테이션을 끌어옴**: `@EnableScheduling`·`@Modulithic`이 슬라이스에 붙는다. 문제가 되면 `@EnableScheduling`을 별도 `@Configuration`으로 분리한다(슬라이스는 `@Configuration` 컴포넌트를 스캔하지 않음).

## 6. 검증 체계

### Step 0 기준선 (이동 전 기록, `docs/superpowers/plans/` 산출물에 첨부)
- Modulith 모듈 ID 19개 덤프
- ArchUnit 규칙별 대상 클래스 수, 아키텍처 테스트 수(root·trading-core)
- 부팅 로그 JPA 리포지토리 수(root·trading 각각)
- `integration` 실행 수(root 14, trading-core 5)

### 상시 규칙
- `archunit.properties` `failOnEmptyShould=true`. 현재 빈 규칙이 드러나면 Step 0 커밋에서 정리한다.
- Modulith verify 프로세스별 분리:
  - `:test` — `ApplicationModules.of(ApiApplication.class)` → api 7개 + shared 3개
  - `:trading-core:test` — `ApplicationModules.of(TradingApplication.class)` → trading-core 9개 + shared 3개
  - 두 모델 ID 합집합 = 기준선 19개(개명 대응: `tradingstats→stats`, `tradingnotify→notify`, `tradingweb→web`, `sharedkernel→kernel`)
- `ArchitectureMapTest`는 두 모델을 합쳐 렌더링하고 프로세스 라벨로 구분한다. 맵은 root 테스트가 생성하므로 `TradingApplication` 모델 계산에 trading-core test classpath(현행 `testImplementation(":trading-core")`)를 그대로 쓴다.
- `GradleModuleBoundaryTest` 신규 규칙: 각 서브프로젝트의 main·test·testFixtures 산출물 클래스는 자기 접두사(`com.kista.api`/`com.kista.tradingcore`/`com.kista.shared`) 아래에만 있다. 이행 중에는 비활성, 마지막 수작업 커밋에서 활성화.
- 각 서브프로젝트 test 태스크의 `--tests` 필터 갱신: `:test --tests 'com.kista.api.architecture.*'`, `:trading-core:test --tests 'com.kista.tradingcore.architecture.*'`.

### 단계별·최종
- 단계마다 `compileJava compileTestJava` + 두 서브프로젝트 아키텍처 테스트. 테스트 수와 규칙별 대상 수가 기준선과 같은지(개명 반영) 확인.
- 최종 1회: 전체 `test`, `integration`(NO-SOURCE 아님, root 14·trading-core 5 실행), `docker build`, 로컬 2-프로세스 부팅(내부 API 크로스콜·Redis Stream 4종·Pub/Sub 확인, JPA 리포지토리 수 기준선 대조).

## 7. 커밋 단계

치환 스크립트는 세그먼트 정확 매칭(`com\.kista\.trading\b(?!stats|notify|web)` 식)으로 `trading`/`tradingstats`/`tradingnotify`/`tradingweb`, `market`/`marketcalendar` 접두사 충돌을 막는다. 스크립트는 레포에 커밋하지 않고 계획 문서에 원문을 첨부한다. 대상: `*.java`의 `package`/`import`/정적 import/FQCN 문자열, `*.kts`, `*.yml`, `*.properties`, `META-INF/**`.

| # | 종류 | 내용 |
|---|---|---|
| 0 | 수작업 | 기준선 기록, `failOnEmptyShould=true` + 빈 규칙 정리, 접두사 경계 규칙(비활성) 추가, Step 0 스파이크 결과 반영(JPA·슬라이스) |
| 1 | 기계적 | `:shared` → `com.kista.shared.{kernel,contract,platform}`, 전 레포 import 치환 |
| 2 | 수작업 | `PlatformAutoConfiguration` 도입, 두 앱의 shared 스캔 항목 제거, `:shared` testFixtures 신설과 테스트 지원 이동, W1 슬라이스 정리 |
| 3 | 기계적 | `:trading-core` → `com.kista.tradingcore.*`(stats/notify/web 개명 포함), root 테스트 import 포함 |
| 4 | 수작업 | `TradingApplication` 루트 이동, `JpaRepositoryConfig`·`@EntityScan`·`@ConfigurationPropertiesScan`·`TradingCoreJpaTestConfig`·`@ContextConfiguration` 삭제, trading-core Modulith 테스트 신설, `tradingcore.web` 모듈 선언 |
| 5 | 기계적 | `:api` → `com.kista.api.*`, `KistaApplication` → `ApiApplication` |
| 6 | 수작업 | `RootJpaScanConfig` 삭제, root Modulith·ArchitectureMap 이원화, 접두사 경계 규칙 활성화 |
| 7 | 수작업 | ArchUnit 패키지 패턴(`..x..`, `resideInAPackage` 문자열) 정밀 수정 — 기계적 치환이 위험한 패턴 |
| 8 | 문서 | `flows.yml` FQCN, 모듈 `CLAUDE.md` 19개 상대 import·위치 이동, `docs/agents/**`(architecture·constraints·commands·testing·modules/*·own-type-ledger·docker-infra), `README.md`, `docs/architecture-map.md`, `AGENTS.md` |

치환 커밋 후 BOM 삽입 여부 확인(`.claude/rules/java-encoding.md`). 커밋 전 검토자 검수는 전역 규칙대로 수행한다.

## 8. 배포·EPR 런북

push·배포는 사용자 요청 시에만 진행한다.

**배포 전**
1. 두 스키마의 미완료 row 확인:
   ```sql
   SELECT event_type, listener_id, count(*) FROM trading.event_publication WHERE completion_date IS NULL GROUP BY 1,2;
   SELECT event_type, listener_id, count(*) FROM public.event_publication  WHERE completion_date IS NULL GROUP BY 1,2;
   ```
   (`docker exec kista-postgres psql -U kista -d kistadb -c ...`)
2. Drain: 미완료 row가 있으면 재발행 완료를 기다린다. 매매 배치 시간대를 피해 배포한다. 영구 실패 row는 내용을 백업·기록한 뒤 `docs/agents/docker-infra.md` "배포 직전 EPR 정리 런북" 절차대로 **삭제**한다. `completion_date`를 채워 완료로 위장하지 않는다.

**배포**
3. 같은 커밋으로 3-role(kista-api·kista-scheduler·kista-trading) 동시 교체. `pre-apply.sh`가 남은 고아를 fail-closed로 막는다 — 막히면 2번으로 돌아간다.

**교체 직후**
4. 1번 쿼리 재실행. blue/green 겹침 동안 구 kista-api가 hook 이후 쓴 구 FQCN row가 있으면 같은 절차로 정리한다.
5. 기동 로그에 `ClassNotFoundException`·EPR 재발행 오류가 없는지, 두 스키마 미완료 row 0인지 확인.

**롤백**
- 구 이미지로 되돌리면 신 FQCN으로 쓰인 미완료 row가 반대로 고아가 된다. 같은 절차(확인 → drain → 삭제)를 역방향으로 수행한다.

**영향 없음**: DB 스키마·Flyway·Redis 키/페이로드.

## 9. 범위 밖

- root 테스트의 `testImplementation(project(":trading-core"))` 완전 제거(아키텍처 테스트가 교차 서브프로젝트 클래스를 읽는 방식 재설계 필요).
- 모듈 경계·NamedInterface 재설계, 클래스 개명(패키지 이동 외).
- kista-ui 변경(API 응답·스키마 이름 불변).

## 10. 계획 단계 보강 (2026-10-13, 구현 계획과 동기화)

구현 계획(`docs/superpowers/plans/2026-10-13-package-root-split.md`) 작성 중 확정된 사항. 위 절과 다르면 이 절이 우선한다.

- **모듈 ID 고정**: 19개 `package-info`에 `@ApplicationModule(id = ...)`를 명시하고 `spring.modulith.detection-strategy: explicitly-annotated`를 쓴다. 패키지 깊이가 바뀌는 중간 커밋에서도 Modulith 검증이 유지되며, 최종 상태에서도 테스트 픽스처 패키지(`support`)가 모듈로 잡히지 않는다. 이행 중에는 `ApplicationModules.of("com.kista")`로 통합 모델을 유지하고, 마지막에 프로세스별 모델(D1)로 전환한다.
- **아키텍처 맵 모듈 키**: 두 모델을 합치면 `notify`·`web`이 충돌하므로 맵의 모듈 키는 `com.kista` 아래 두 세그먼트(`api.notify`, `tradingcore.trading`, `shared.kernel`)다.
- **빈 구성 회귀 가드**: 두 앱의 컨텍스트 로드 테스트(trading-core는 신설)가 JPA 리포지토리 수, `SecurityFilterChain` 1개, root 컨텍스트에 trading-core 빈 없음을 단언한다. 자동구성은 `@AutoConfigureOrder(HIGHEST_PRECEDENCE)`로 Boot 기본 빈보다 먼저 평가한다.
- **경계 규칙**: 6절의 "이행 중 비활성" 대신 마지막 수작업 Task에서 바로 활성 상태로 추가한다. trading-core 테스트의 `com.kista.shared.platform.scheduling`(SchedulerLockService DB 테스트)만 명시 예외.
- **모듈 문서 위치**: `docs/agents/modules/{api,tradingcore,shared}/<모듈ID>.md`.
