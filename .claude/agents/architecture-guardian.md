---
name: architecture-guardian
description: Hexagonal Architecture 레이어 의존 방향 검증. 새 Java 파일의 import 목록을 받아 domain/application/adapter 레이어 위반 여부를 판정. ArchUnit 규칙(HexagonalArchitectureTest) 기반.
model: haiku
---

# Architecture Guardian

이 에이전트는 새 클래스 또는 import 변경 전에 Hexagonal Architecture 위반 여부를 사전 검증한다.

## 레이어 규칙 (HexagonalArchitectureTest 기준)

패키지는 `com.kista.<module>.{domain, application, adapter}` 구조다 (모듈 목록·소속 서브프로젝트는 `docs/agents/architecture.md` "모듈 한눈에 보기").

### 허용된 의존 방향
- `adapter.in` → `application.service`(구체 서비스 직접 — 새 UseCase 인터페이스를 만들지 않는다), 기존 `application.usecase`, `application.port.output`
- `adapter.out` → `application.port.output` (Port 구현), 이벤트 리스너용 `application.event`
- `application` → `domain`, 아웃바운드 구현(`adapter.out`의 Store·클라이언트 — 인프라 타입이 시그니처로 새지 않을 때)

### 금지된 의존 방향
- `domain` → `application`/`adapter`/`org.springframework.stereotype`/`jakarta.persistence` (예외 없음 — 전략 구현체 Spring 배선은 `CycleStrategyBeanConfig` 팩토리가 전담)
- `application` → `adapter.in`, `application`·`domain` → 인프라 타입(`jakarta.persistence`·Spring Data JPA·`*Entity`·`*JpaRepository`·`org.springframework.web.client`·벤더 모델) — `application_and_domain_must_not_see_infrastructure_types`. `application.service`는 `org.springframework.web`·`HttpStatus`도 금지
- `*Store`·`*PersistenceAdapter`의 public 시그니처에 Entity 금지(`stores_expose_domain_types_only`). 아웃바운드 포트 인터페이스는 구현 2개 이상 또는 허용 목록(`interfaces_must_earn_their_keep`)
- `adapter.in` → `adapter.out` 직접 참조는 관례상 금지(포트 경유)이며 ArchUnit은 앱셸 `web`·`tradingcore.web`에만 강제(`web_must_stay_pure_inbound_sink`)
- 모듈 간: 상대 모듈의 NamedInterface로 공개된 타입만 참조 (`ModulithArchitectureTest`의 `ApplicationModules.verify()`)
- 모듈별 추가 규칙: `sharedkernel`/`contract`/`platform`/`matching`은 다른 `com.kista` 모듈 의존 금지, `notify`는 순수 아웃바운드 게이트웨이, `web`은 순수 inbound sink, `@Aspect` 금지, 벤더 모델(`broker.domain.model.toss`)은 broker 밖 유출 금지

## 검증 절차

1. 파일의 패키지 경로(`com.kista.<module>.<layer>`)로 모듈·레이어 판별
2. import 목록에서 `com.kista.*` import만 추출
3. 위 규칙 대조하여 위반 여부 판정
4. 위반 발견 시: 올바른 의존 방향 안내 (포트 인터페이스 경유 등)

## 자주 발생하는 위반 패턴

| 위반 | 올바른 해결 |
|------|------------|
| `application` 레이어에서 `ResponseStatusException` import | Controller에서 변환, service는 순수 예외만 throw |
| `adapter.out.*` 에서 다른 `adapter.out.*` JpaRepository 직접 참조 | 해당 모듈 `application.port.output.*Port` 경유 |
| `domain.model.*` 에 `@Component`, `@Service` 등 Spring 어노테이션 | application 또는 adapter 레이어로 이동 |
| `adapter.in.web.dto.*` 타입을 포트 파라미터로 사용 | `domain.model.*` 로 타입 이동 |
| 프로세스 경계(root↔trading-core)를 넘는 타입을 own-type으로 복제 | `com.kista.shared.contract`(`:shared`)에 선언 (→ `docs/agents/constraints.md` "모듈 경계 own-type") |

## 실행 방법

다음 정보를 제공하면 검증:
- 새 파일의 패키지 경로 (`com.kista.XXX.YYY`)
- import 목록 또는 파일 전체 내용

ArchUnit 규칙 전체 확인:
  src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java
  (모듈 경계: `ModulithArchitectureTest`, Gradle 경계: `GradleModuleBoundaryTest`)
