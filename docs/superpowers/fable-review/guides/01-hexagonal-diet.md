# 지침서 01. 헥사고날 다이어트 — 거울 인터페이스와 순수 위임층 제거

> **실행 세션은 먼저 맨 끝의 9절 "실행 전 보정"과 `00-roadmap.md` 9절(공통 규칙)을 읽는다.** 본문과 다르면 그쪽이 우선한다. (2026-11-14)

작성 2026-11-11 (Fable). 근거 측정: `findings/02-type-duplication.md` B·C절, `findings/01-vertical-slices.md` 패턴 3, `findings/04-test-bloat.md`.

## 1. 현재 구조와 문제

- `application/usecase` 52개 + `application/port/output` 97개 = 인터페이스 149개(선언 2,919줄). **138개(92.6%)가 구현 1개**이고 UseCase 52개는 전부 구현 1개다. 메서드 1개짜리가 49개.
- UseCase의 소비자는 대부분 컨트롤러 1개다. 모듈 밖에서 소비되는 UseCase는 셋뿐이다: `UserUseCase`(admin 2곳), `AccountUseCase`(trading 1곳), `PrivacyTradeValidationUseCase`(trading 1곳).
- 실제 다형성이 있는 포트는 11개다: `UserNotificationPort`(3), `BrokerConnectionTestPort`(6), `CryptoExchangePort`(2), `BrokerCapabilitiesPort`와 그 상위 포트 7개(브로커 어댑터 4 + `BrokerRouter`).
- 구현이 다른 모듈에 있는 의존 역전 포트는 4개다: `ApprovalPolicyPort`, `BrokerEnabledPort`, `AccountOpenOrderCancelPort`, `MockSimulationDataPort`.
- 로직 없이 위임만 하는 클래스가 약 20개 800줄(추정)이다. 예: `TradingExecutionFacade`(80줄, 10개 메서드 전부 위임), `KisBrokerAdapter`(public 16개 중 14개 위임), 알림 `*Notifier` 군.

이 인터페이스들은 "시그니처를 한 번 더 적은 것" 이상의 일을 하지 않는다. 메서드 하나를 바꾸면 인터페이스·구현·mock 세 곳을 고친다. 지금 이 구조를 지키는 것은 ArchUnit 규칙 3개다(`src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java`).

- `inbound_adapters_must_not_depend_on_application_layer` (:346)
- `application_must_not_depend_on_adapter` (:356)
- `rest_controllers_must_not_depend_on_application_implementations` (:466)

## 2. 목표 구조

**원칙: 의존 방향은 지키고, 거울 인터페이스는 없앤다.** 헥사고날에서 실제로 값을 하는 것은 "도메인과 application이 JPA·HTTP·벤더 타입을 모른다"는 성질이지, 구현이 하나뿐인 인터페이스의 존재가 아니다.

인터페이스를 두는 조건은 셋 중 하나다.

1. 구현이 2개 이상이다(위 11개).
2. 구현이 다른 모듈에 있어 순환을 끊는다(위 4개).
3. 프로세스 밖 계약이다(`shared.kernel`의 순수 포트 등 `:shared`가 선언하고 각 프로세스가 구현하는 것).

그 밖은 전부 구체 클래스를 직접 쓴다.

| 대상 | 현재 | 목표 |
|---|---|---|
| UseCase 52개 | 컨트롤러 → `XxxUseCase` → `XxxService` | 컨트롤러 → `XxxService`. `application/usecase` 패키지 삭제 |
| 모듈 밖에서 쓰는 서비스 3개 | "usecase" NamedInterface로 공개 | 해당 서비스 클래스에 `@NamedInterface("api")`를 타입 단위로 붙여 그 클래스만 공개 |
| 단일 구현 영속 포트 | 서비스 → `XxxPort` → `XxxPersistenceAdapter` → `XxxJpaRepository` | 서비스 → `XxxStore`(현 PersistenceAdapter를 개명, 공개 시그니처는 도메인 타입만) → `XxxJpaRepository` |
| 단일 구현 외부 연동 포트 | 서비스 → `XxxPort` → `XxxAdapter`/`XxxClient` | 서비스 → 구현 클래스 직접 |
| 순수 위임 클래스 | 호출자 → 위임 클래스 → 실제 빈 | 호출자 → 실제 빈 |

ArchUnit 규칙은 다음으로 바꾼다. 지키려는 성질을 패키지가 아니라 타입으로 표현한다.

- 삭제: 위 3개 규칙, `inbound_port_interfaces_must_have_UseCase_or_Query_suffix`.
- 신설 `application_and_domain_must_not_see_infrastructure_types`: `..application..`·`..domain..`은 `jakarta.persistence..`, `org.springframework.data.jpa..`, `*Entity`, `*JpaRepository`, `org.springframework.web.client..`, 벤더 모델 패키지에 의존하지 않는다.
- 신설 `stores_expose_domain_types_only`: `*Store`의 public 메서드 시그니처에 `*Entity`가 나오지 않는다.
- 신설 `interfaces_must_earn_their_keep`: `application/port/output`의 인터페이스는 구현이 2개 이상이거나 허용 목록(의존 역전 4개 + 이후 추가분)에 있어야 한다. 거울 인터페이스의 재발을 막는 가드다.
- 유지: `domain_must_not_depend_on_outer_layers`, `outbound_port_interfaces_must_have_Port_suffix`, `persistence_jpa_repositories_must_be_package_private`, 모듈 경계(`ApplicationModules.verify()`).

### 이렇게 정한 이유

- 테스트 대역 때문에 인터페이스가 필요하지는 않다. Mockito는 클래스를 mock한다. 테스트의 `@MockitoBean` 대상은 UseCase 55곳, Port 96곳인데 타입 이름만 바뀐다.
- Spring 프록시(`@Transactional` 등)는 CGLIB 클래스 프록시로 동작한다(Boot 기본 `proxyTargetClass=true`).
- 모듈 경계는 Modulith가 지킨다. NamedInterface는 패키지뿐 아니라 타입에도 붙일 수 있어, 인터페이스 없이도 공개 범위를 좁게 유지한다.

### 기각한 대안

- **인터페이스를 유지하고 줄만 줄인다(메서드 병합 등)**: 세 곳을 함께 고치는 비용이 그대로다.
- **컨트롤러가 JpaRepository를 직접 쓰는 단층 구조**: 트랜잭션 경계와 소유권 검증이 컨트롤러로 흩어진다. 매매·원장 경로에서 받아들일 수 없다.
- **application과 adapter 패키지 구분 자체를 없앤다**: 인프라 타입 누출을 막는 가드를 패키지 없이 쓰기 어렵고, 모듈 문서·NamedInterface가 전부 이 구분에 기대고 있다. 얻는 것에 비해 변경이 크다.

## 3. 재심한 과거 결정

| 결정 | 출처 | 재심 결과 |
|---|---|---|
| S-11 "UseCase 구현체 1개짜리 22개 유지"(2026-07-11) | memory `project_2026_07_11_full_review.md` | **뒤집는다.** 당시 22개가 지금 52개로 늘었고 이유가 기록돼 있지 않다 |
| S-24 `StrategyUseCase` command/query 미분리 유지 | `modules/tradingcore/trading.md:8` | 대상 인터페이스가 사라져 소멸 |
| S-48 broker 밖 기능은 `broker.application.port.output`의 `*Port`로 노출 | `constraints.md` | **유지.** 브로커 포트는 다형성이 실제로 있다 |
| C-27 `OrderPort`에 default `findByIdOrThrow`를 두지 않음 | backlog L638 | 이유(default 메서드가 mock에서 NPE)가 소멸한다. 구체 클래스가 되면 일반 메서드로 둘 수 있다. 통일 여부는 지침서 02에서 |

## 4. 예상 감량과 위험

- main: 인터페이스 선언 약 2,700줄과 파일 약 134개, 순수 위임 클래스 약 800줄. 합 **약 3,000~3,500줄(추정), 파일 약 150개**.
- test: 줄 수는 거의 그대로다(타입 이름 치환).
- 위험 1: 클래스 프록시는 final 클래스·final 메서드에서 동작하지 않는다. 서비스에 `final`이 붙어 있으면 뗀다. `:test --tests '*ApplicationContextLoadTest'`가 잡는다.
- 위험 2: 같은 인터페이스를 주입받던 곳이 구체 클래스를 받으면서 순환 빈 참조가 드러날 수 있다. 컨텍스트 로드 테스트가 잡는다.
- 위험 3: 문서 드리프트. `constraints.md`의 "포트 인터페이스 위치·네이밍", `architecture.md`의 모듈 표("usecase" NamedInterface), 각 `modules/**/*.md`, `flows.yml`의 `code:` 참조가 바뀐다. 지침서 09(문서 개편)와 함께 고친다.
- 동작 변경은 없다. 순수 구조 변경이다.

## 5. 선행 조건

- 동작 보존 근거는 기존 테스트 스위트 전체다. 새 테스트는 필요 없다.
- 시작 전에 `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`가 초록인지 확인하고 기준 커밋을 적어 둔다.
- 영속 포트 제거(단계 C)는 **지침서 02의 영속 구조 결정 뒤에** 실행한다. 02가 `PersistenceAdapter`의 형태를 바꾸므로 순서를 뒤집으면 같은 파일을 두 번 고친다. 단계 A·B·D는 지금 실행할 수 있다.

## 6. 태스크

모든 태스크의 완료 판정: `./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"`가 성공이고, 해당 모듈 테스트와 `./gradlew :test --tests 'com.kista.api.architecture.*' :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"`가 통과한다. 전체 스위트는 단계 끝에 1회.

### 단계 A. ArchUnit 규칙 개정 (Opus)

1. 2절의 삭제·신설 규칙을 `HexagonalArchitectureTest`에 반영한다. 신설 규칙 3개는 처음에는 현재 코드에서 통과해야 한다(`interfaces_must_earn_their_keep`만 단계 C가 끝날 때 켠다. 그 전에는 `@Disabled`와 사유를 단다).
2. trading-core 쪽 아키텍처 테스트에 같은 규칙이 복제돼 있으면 함께 고친다.
3. `constraints.md`의 관련 절을 새 원칙(2절의 조건 3개)으로 다시 쓴다.

### 단계 B. UseCase 인터페이스 제거 (Sonnet, 모듈 단위로 반복)

순서: 작은 모듈부터. `marketcalendar` → `privacy` → `account` → `api.market` → `api.notify` → `api.benchmark` → `api.user` → `api.admin` → `api.finance` → `stats` → `trading`.

모듈마다:

1. `application/usecase/*.java` 목록과 각 구현 클래스를 짝짓는다(`grep -rn "implements .*UseCase\|implements .*Query"`).
2. 한 서비스가 인터페이스 여러 개를 구현하면 그대로 둔다(서비스를 합치거나 쪼개지 않는다. 그건 다른 지침서의 일이다).
3. main·test 전체에서 인터페이스 타입 참조를 구현 클래스 이름으로 바꾸고 import를 고친다. 구현 클래스에서 `implements`와 `@Override`를 지운다. 인터페이스에 달려 있던 주석은 구현 메서드로 옮긴다(주석 규칙: `//` 한 줄).
4. 인터페이스 파일과 `application/usecase/package-info.java`를 지운다.
5. 모듈 밖 소비가 있는 셋(`UserUseCase`, `AccountUseCase`, `PrivacyTradeValidationUseCase`)은 구현 서비스 클래스에 `@NamedInterface("api")`를 붙이고, 그 서비스가 있는 `application/service` 패키지 전체가 공개되지 않는지 `ModulithArchitectureTest`로 확인한다.
6. 구현 클래스가 package-private이면 컨트롤러에서 보이도록 public으로 올린다.
7. 모듈 문서(`docs/agents/modules/<루트>/<모듈>.md`)의 NamedInterface 서술을 고친다.

모듈 하나가 끝날 때마다 커밋한다(`refactor(<모듈>): UseCase 인터페이스를 제거하고 서비스를 직접 주입한다`).

### 단계 C. 단일 구현 포트 제거 (Sonnet, 지침서 02 이후)

1. 포트 97개를 분류한 표를 먼저 만든다: 구현 수, 구현 위치(같은 모듈/다른 모듈), 종류(영속/외부 HTTP/Redis/알림/기타). 2절의 조건 3개에 해당하면 유지 목록에 넣는다.
2. 영속 포트: `XxxPersistenceAdapter`를 `XxxStore`로 개명하고 포트 참조를 치환한다. 02가 정한 형태에 맞춘다.
3. 외부 연동 포트: 구현 클래스 이름으로 치환한다.
4. 포트가 "port" NamedInterface로 다른 모듈에 공개돼 있었다면, 구현 클래스에 타입 단위 `@NamedInterface`를 붙인다. 이때 구현 클래스가 `adapter/out` 아래라 모듈 밖에서 adapter를 참조하게 되는 경우는 **인터페이스를 유지한다**(조건 2의 확장: 모듈 밖에 인프라 구현을 노출하지 않는다). 이 목록을 태스크 보고에 적는다.
5. `interfaces_must_earn_their_keep` 가드를 켠다.

### 단계 D. 순수 위임 클래스 제거 (Opus가 목록 판정, Sonnet이 적용)

1. `findings/02-type-duplication.md` C-1·C-2 표의 후보를 하나씩 읽고 판정한다. 삭제 기준: 모든 public 메서드가 다른 빈 호출 한 줄이고, 그 호출에 예외 변환·트랜잭션 경계·이벤트 발행·로그가 붙어 있지 않다.
2. 유지: 이벤트 리스너, 스케줄러, Redis 컨슈머(진입점이다). 모듈 경계 포워더(`UserModuleFinanceMemberAdapter` 등)는 단계 C의 4번 규칙을 따른다.
3. 브로커 facade(`KisBrokerAdapter` 등)는 여기서 건드리지 않는다. 지침서 04가 다룬다.
4. `*ContractMapper` 3개와 내부 HTTP 포워더는 지침서 05(프로세스 전제)가 다룬다.

## 7. 하지 말 것

- 서비스의 책임을 합치거나 쪼개지 않는다. 이 지침서는 간접층만 걷어낸다.
- 브로커 포트(`broker/application/port/output`)의 다형성 포트는 건드리지 않는다.
- `:shared`의 포트와 계약 타입은 건드리지 않는다.
- 메서드 시그니처·이름을 바꾸지 않는다(치환 범위가 흐려진다).
- 인터페이스 삭제와 로직 수정을 한 커밋에 섞지 않는다.

## 8. 다른 지침서와의 관계

- 02(영속·DTO): 단계 C의 선행.
- 04(브로커): 브로커 facade와 포트 묶음.
- 05(프로세스 전제): 내부 HTTP 어댑터, contract mapper, 사용자 복제본.
- 07(테스트): 이 지침서 뒤에 `@MockitoBean` 대상이 구체 클래스로 바뀐 상태에서 진행.
- 09(문서): `constraints.md`·`architecture.md`·모듈 문서의 포트 서술.

## 9. 실행 전 보정 (2026-11-14 — 본문과 다르면 이 절이 우선)

공통 규칙은 `00-roadmap.md` 9절. 측정 원본은 `findings/23`.

전제 정정:

- 단계 A가 지우는 규칙 세 개의 줄 번호는 `HexagonalArchitectureTest`의 346·356·465다(본문의 466은 465).
- A-2의 "trading-core 쪽에 같은 규칙이 복제돼 있으면"은 대상이 없다. trading-core의 architecture 테스트는 4개(Modulith·OpenApi·StreamSubscriber·VendorModel)뿐이고 루트 `HexagonalArchitectureTest`가 `com.kista` 전체를 본다.
- UseCase 52개 전부 구현 1개, 모듈 밖 소비 3종 4곳은 지금도 맞다.

같은 커밋에서 고칠 가드·선언:

- 단계 B에서 usecase 패키지를 지울 때 `package-info.java` 9개의 `@NamedInterface("usecase")`(api의 benchmark·admin·user·finance·market, tradingcore의 account·trading·stats·privacy)와 모듈 `package-info` 주석 6개를 같이 고친다. 먼저 `git grep -n "::usecase"`로 다른 모듈의 `allowedDependencies` 참조가 있는지 본다.
- `web_must_stay_pure_inbound_sink`(`:248-260`)는 `api.web`·`tradingcore.web`이 `application.service`에 의존하는 것을 막는다. 지금 web 셸의 다른 모듈 usecase import는 0건이라 걸리지 않는다. 걸리게 되면 규칙을 고치지 말고 그 컨트롤러를 소유 모듈의 `adapter/in/web`으로 옮긴다(05 R3과 같은 방향).

기본값:

- A-1의 `interfaces_must_earn_their_keep`은 `@Disabled`로 넣지 않는다. 단계 C 전에는 현재 위반 전체를 허용 목록에 넣고 켠다(07의 7절 우선).
- 서비스에 `final`이 붙어 컨텍스트 로드가 실패하면 `final`을 뗀다.
- 한 모듈의 단계 B에서 순환 빈 참조가 나오면 그 모듈의 변경만 되돌리고(그 모듈은 인터페이스 유지) 다음 모듈로 간다. 되돌린 모듈과 순환 경로를 이 절 아래에 적는다.
- 프록시 방식(CGLIB)은 문서를 찾지 않고 `ApplicationContextLoadTest`·`TradingApplicationContextLoadTest`와 `@Transactional` 서비스의 기존 통합 테스트 통과로 확인한다.

배포: 동작 변경이 없어 승인 대상이 아니다. 모듈 단위로 커밋한다.
