# StrategyVersion/InfiniteDetail/VrDetail trading 이관 설계 (strategy-config 이전 서브프로젝트 B)

## 배경/목적

strategy-config 이전([[2026-08-31-legacy-module-catalog-design]] 4단계, account 완료 후 마지막 잔여 모듈)을 세 서브프로젝트로 쪼갠다 — **A(완료, 커밋 a81e76eb)**: `Strategy` nested enum 4종을 `com.kista.sharedkernel`로 이관. **B(이 문서)**: `StrategyVersion`/`StrategyInfiniteDetail`/`StrategyVrDetail` + `VrStrategyLifecycle`을 trading 소유로 이관. **C(후속 스펙)**: 남는 `Strategy` 애그리게이트로 얇은 strategy-config 모듈 신설 + admin↔strategy-config 순환 해소.

**B가 필요한 이유**: `CycleSnapshotCreator.reconfigureVrCycle()`(trading, `@Transactional`)이 VR 운영 중 재설정 시 `StrategyVersionPort`(버전 소프트삭제+신규저장)와 `VrStrategyLifecycle.saveVersionDetail()`(`strategy_vr_version` 저장)을 `StrategyCycleVrPort`/`CyclePositionPort`(trading 소유)와 같은 트랜잭션에서 호출한다. 애초 이 계획을 처음 브리핑할 때는 "strategy-config↔trading 원자적 트랜잭션 결합이라 own-type/이벤트로 못 푼다"고 판단했으나, 이번 설계 과정에서 다시 짚어보니 **Spring Modulith는 모듈마다 별도 트랜잭션 매니저를 두지 않는다** — `@Transactional` 메서드가 다른 CLOSED 모듈의 공개 "port"를 호출하는 것 자체는 이미 이 코드베이스에 흔한 정상 forward 의존(예: 레거시 `StrategyService`가 trading의 `CyclePositionPort`/`StrategyCyclePort`를 지금도 직접 호출)이라 원자성 자체는 문제가 아니었다. 진짜 논점은 소유권이다 — `StrategyVersion`/`StrategyInfiniteDetail`/`StrategyVrDetail`을 실제로 참조하는 코드를 grep했더니 trading(`CycleSnapshotCreator`/`VrStrategyLifecycle`/`CycleOrderComputer` 등 실행 로직)과 레거시 `StrategyService`(등록·조회 조립) 뿐이었다 — 이 세 타입은 "전략 등록 설정"이 아니라 **버전별 실행 파라미터**다. trading 소유로 옮기면 `reconfigureVrCycle`은 trading 내부 호출이 되어 모듈 경계 자체가 사라진다.

## 결합도 실측 (2026-09-02 grep 기준)

| 대상 타입 | 외부 참조 | 판정 |
|---|---|---|
| `StrategyVersion`/`StrategyInfiniteDetail`/`StrategyVrDetail` | trading(`CycleSnapshotCreator`/`VrStrategyLifecycle`/`CycleOrderComputer`/`InfiniteCycleOrderStrategy` 등 다수) + 레거시 `StrategyService`/`VrStrategyLifecycle` + `stats.domain.backtest.BacktestEngine`(순수 값 계산용, 포트 미사용) | trading 소유로 전환해도 admin/broker 등 다른 모듈에 실질 의존 없음 — 안전 |
| `StrategyDetail.VrSummary`(nested record) | 필드 16개 전부 `StrategyVrDetail`+`StrategyCycleVrDetail`+pool 파생값(trading 데이터)뿐, `Strategy`(config) 필드 0개 | 복제·매핑이 아니라 **통째 승격** 대상 — nested record를 trading top-level 타입으로 이동하면 됨 |
| `StrategyEntity`/`StrategyJpaRepository`/`StrategyPersistenceAdapter`/`PersistenceSupport`(레거시) | `Strategy` 애그리게이트 자체 | 이번 B 스코프 아님 — C(strategy-config 신모듈)로 잔류 |

## 이관 대상 (파일 단위)

**domain** (`com.kista.domain.model.strategy` → `com.kista.trading.domain.model`, trading "domain" NamedInterface에 병합):
- `StrategyVersion.java`
- `StrategyInfiniteDetail.java`
- `StrategyVrDetail.java`
- `StrategyDetail.VrSummary`(nested) → `com.kista.trading.domain.model.VrSummary`(top-level 신설, 필드 그대로 승격)

**port** (`com.kista.application.port.output` → `com.kista.trading.application.port.output`, trading "port" NamedInterface 6→9개):
- `StrategyVersionPort.java`
- `StrategyInfiniteDetailPort.java`
- `StrategyVrDetailPort.java`

**persistence** (`com.kista.adapter.out.persistence.strategy` → `com.kista.trading.adapter.out.persistence`, internal):
- `StrategyVersionEntity.java` / `StrategyVersionJpaRepository.java` / `StrategyVersionPersistenceAdapter.java`
- `StrategyInfiniteEntity.java` / `StrategyInfiniteJpaRepository.java` / `StrategyInfiniteDetailPersistenceAdapter.java`
- `StrategyVrVersionEntity.java` / `StrategyVrVersionJpaRepository.java` / `StrategyVrDetailPersistenceAdapter.java`
- 세 어댑터가 쓰던 레거시 `PersistenceSupport.findOrCreate()` 호출은 trading에 이미 존재하는 동명 `com.kista.trading.adapter.out.persistence.PersistenceSupport`로 자동 해결(같은 패키지 진입) — 레거시 `PersistenceSupport.java`는 이동하지 않고 `StrategyPersistenceAdapter`(잔류) 전용으로 남긴다
- 전부 `kista` 스키마(`@Table(schema="kista")`) — DB 재생성·Flyway 변경 없음, 패키지 이동만
- **주의(load-bearing 주석 보존 필수)**: `CycleSnapshotCreator.reconfigureVrCycle()`의 `nextVersionNo는 반드시 소프트 삭제보다 먼저 계산` 주석 — `StrategyVersionEntity`의 `@SQLRestriction("deleted_at IS NULL")`이 `findMaxVersionNoByStrategyId` 커스텀 쿼리에도 자동 적용되므로, 순서를 바꾸면 소프트 삭제한 버전과 동일한 versionNo가 재계산돼 `uq_strategy_version_strategy_version_no` 위반. 이관 시 이 순서와 주석 그대로 보존

**usecase 승격** (`com.kista.application.service.strategy.VrStrategyLifecycle` → 인터페이스+구현 분리):
- 신규 인터페이스 `com.kista.trading.application.usecase.VrStrategyDetailUseCase`(trading "usecase" 공개) — 기존 6개 메서드(`saveVersionDetail`/`saveInitialCycleDetail`/`findSummary`/`findVrDetailsByVersionIds`/`findCycleVrDetailsByCycleIds`/`buildSummary`)를 계약화. 전부 package-private이던 것을 public으로 승격(레거시 `StrategyService`가 크로스모듈로 호출해야 하므로)
- 구현체는 이름 그대로 `com.kista.trading.application.service.VrStrategyLifecycle`(internal, `@Component`)로 이동해 인터페이스 구현
- `CycleSnapshotCreator`(trading, 같은 모듈)는 이 usecase를 그대로 주입받아 사용 — 모듈 내부 호출이라 인터페이스 경유 여부와 무관하게 항상 허용됨

## 호출부 변경 (레거시 `StrategyService`, 로직 변경 없이 import/타입만 교체)

- `StrategyVersionPort`/`StrategyInfiniteDetailPort`/`StrategyVrDetailPort` import 경로만 `com.kista.trading.application.port.output`로 변경
- `VrStrategyLifecycle` 필드 타입을 `VrStrategyDetailUseCase`로 변경(구현 주입은 Spring이 처리, 코드 변경 없음)
- `StrategyDetail` record의 `vrSummary` 필드 타입을 `com.kista.trading.domain.model.VrSummary`로 변경(nested `VrSummary` 제거)
- `StrategyService`는 register()에서 여전히 NOT_SUPPORTED(비트랜잭션), update()/delete()는 class-level `@Transactional` 그대로 유지 — trading의 공개 "port"를 같은 트랜잭션에서 호출하는 것은 정상 forward 의존이라 B에서 손댈 필요 없음(당초 브리핑에 있던 "update/delete도 원자성 문제"는 오판정이었음, advisor 확인 완료)
- `TradingCycleController`의 `TradingCycleResponse.VrSummary.from(StrategyDetail.VrSummary)` — 파라미터 타입만 `com.kista.trading.domain.model.VrSummary`로 교체(순수 시그니처 변경, 로직 무변경)

## 테스트/검증

1. `./gradlew compileJava compileTestJava` — 이동 후 즉시 컴파일 확인
2. `./gradlew test --tests 'com.kista.architecture.*'` — ArchUnit `HexagonalArchitectureTest` + Modulith `ApplicationModules.verify()`. **주의**: 이 시점 레거시 `StrategyService`는 여전히 레거시 OPEN 패키지에 남아있으므로(C 이전), 이 verify() 통과가 "strategy-config 전체가 깨끗하다"는 증거는 아니다 — C 단계에서 Strategy 애그리게이트를 CLOSED로 선언할 때 다시 실측해야 한다
3. 기존 `VrReconfigureServiceTest`/`StrategyServiceTest`/`CycleRotationServiceTest` 등 관련 단위테스트 그대로 통과해야 함(동작 변경 없음, 순수 재배치)
4. `DataJpaTestBase` 계열(`StrategyPersistenceAdapterTest`, `StrategyVrDetailPersistenceAdapterTest` 등) 포함한 전체 `./gradlew test` 최종 1회 — 실제 Hibernate 부팅으로 `@Table(schema)` 불일치를 잡는 유일한 신호(ArchUnit/컴파일은 스키마 불일치를 못 잡음)
5. `stats.domain.backtest.BacktestEngine`이 `StrategyVrDetail`을 이제 `com.kista.trading.domain.model`에서 import하는지 확인 — stats→trading은 이미 7개 파일에서 존재하는 정상 forward라 신규 순환 아님

## 스코프 아웃

- `Strategy`/`StrategyEntity`/`StrategyPort`/`StrategyUseCase`/`RegisterStrategyCommand`/`UpdateStrategyCommand`/`StrategySeedPreview` — 그대로 레거시 위치 잔류, C에서 strategy-config 신모듈로 이전
- admin↔strategy-config 순환(`RuntimeSettingsPort` 역참조) 해소 — C 대상
- strategy-config `@ApplicationModule(CLOSED)` 선언 자체 — C에서 수행(B는 trading 쪽 확장만, strategy-config는 여전히 미선언 상태로 남음)
