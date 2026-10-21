# com.kista.common 모듈 소멸 설계 (모듈 경계 재구성 #3)

## 배경

`com.kista.common`은 Spring Modulith 점진 이전 중 임시 개방된 `@ApplicationModule(Type.OPEN)` 패키지다. `sharedkernel`/`platform`과 달리 outbound-zero를 강제하는 ArchUnit 규칙이 없어 leaf 불변식이 실제로는 보장되지 않는다 — 현재 그 틈을 `CycleLookups`가 `com.kista.trading`(도메인+포트)을 import해 증명하고 있다. 남은 4파일을 각자 성격에 맞는 목적지로 옮기고 패키지 자체를 소멸시킨다.

이 작업은 `docs/agents/project_module_boundary_roadmap.md`(메모리, 원 아티팩트는 삭제됨)에 기록된 모듈 경계 재구성 로드맵의 #3이다. #1(matching 커널 추출)·#2(OrderDirection/OrderType 등 enum sharedkernel 승격)·#4(notify 분리)는 이미 완료·병합됐다(main, commit `564b194d` 기준) — 이 스펙은 #3만 다룬다.

## 현재 상태 (조사 결과)

`com.kista.common`에 4개 파일 존재:

| 파일 | 성격 | outbound 의존 | 사용처 |
|---|---|---|---|
| `CycleLookups` | 헬퍼(정적 메서드 1개) | `trading.domain.model.StrategyCycle` + `trading.application.port.output.StrategyCyclePort` | 9곳(admin 2, trading 7) + 테스트 1 |
| `Sha256` | 순수 유틸 | JDK only | `TokenService`(user), `TossRedisTokenStore`(broker) + 테스트 1 |
| `TimeZones` | 순수 상수 | JDK only | 25개 파일, 도메인 클래스 포함(`trading.domain.model.DstInfo`/`StrategyCycle`, `market.domain.model.MarketSessionSnapshot`) |
| `UsTradeDates` | 순수 유틸 | JDK only | 정확히 4곳: `KisTradingApi`/`KisPriceApi`(broker.kis), `TossPriceApi.getClosingPrice`(broker.toss), `MarketCalendarPersistenceAdapter`(market) — `docs/agents/constraints.md` "시간 기준 정책" allowlist와 실사용이 정확히 일치함을 확인 |

`CycleLookups`만이 실제 순환 위험(common→trading 엣지)을 갖고 있다 — 나머지 3개는 JDK-only 순수 유틸이라 위치 이동은 순수 정리다.

## 이동 계획

### 1. `CycleLookups` → `StrategyCyclePort` default 메서드

`requireLatestCycle(StrategyCyclePort, UUID)` 정적 메서드를 `StrategyCyclePort` 인터페이스의 default 메서드 `requireLatestByStrategyId(UUID strategyId)`로 이동한다. 내부 구현은 기존 `findLatestByStrategyId(strategyId)` 호출 + `orElseThrow` 그대로 재사용.

호출부 9곳 전부 `CycleLookups.requireLatestCycle(strategyCyclePort, X)` → `strategyCyclePort.requireLatestByStrategyId(X)`로 치환 (admin 2곳: `AdminReorderService`/`AdminTradeCorrectionService`, trading 7곳: `MockSimulationDataAdapter`/`TradingService`×2/`StrategyService`/`ManualTradingService`/`OrderCancelService`/`VrReconfigureService`).

이 메서드는 `trading`의 "port" NamedInterface로 이미 공개된 `StrategyCyclePort`에 얹히므로 admin의 기존 소비 방식(포트 인터페이스 직접 참조)이 그대로 유지된다 — admin↔trading 경계에 변화 없음.

**⚠ Mockito 낙진 (advisor 검토로 발견, `docs/agents/testing.md` "Mockito + interface default 메서드 주의" 항목 그대로 적용됨):** `CycleLookups.requireLatestCycle`은 정적 메서드라 실제 실행되며 내부에서 mock의 `findLatestByStrategyId`를 호출하는 방식으로 기존 stub이 통했다. `requireLatestByStrategyId`를 `StrategyCyclePort`의 default 메서드로 만들면 **Mockito가 default 메서드를 override해 null을 반환** — `findLatestByStrategyId` stub만 있고 `requireLatestByStrategyId` stub이 없으면 본문이 실행되지 않고 그대로 NPE가 난다. `compileJava`로는 잡히지 않는다.

`grep -rl "StrategyCyclePort" src/test`로 확인한 실제 낙진 대상(예약 재확인 필요, 조사 시점 기준) — `findLatestByStrategyId`를 stub하는 12개 테스트 파일: `AdminReorderServiceTest`/`AdminTradeCorrectionServiceTest`/`BatchContextFactoryTest`/`StrategyCyclePersistenceAdapterTest`(이 파일은 실제 어댑터 구현 테스트라 무관 — 실제 영향 대상에서 제외 확인 필요)/`ManualTradingServiceTest`/`OrderCancelServiceTest`/`StrategyServiceTest`/`TradingBuyCompetitionSimulatorTest`/`TradingPreviewServiceTest`/`TradingServiceTest`/`VrReconfigureServiceTest` + `CycleLookupsTest`(삭제 대상). 각 파일에서 `when(strategyCyclePort.findLatestByStrategyId(...))` stub 옆에 `when(strategyCyclePort.requireLatestByStrategyId(...))` stub을 추가하거나(둘 다 호출되는 경로가 남아있다면), `requireLatestCycle`을 호출하는 프로덕션 코드 경로가 실제로 `requireLatestByStrategyId`만 부르도록 바뀐 지점은 `findLatestByStrategyId` stub을 `requireLatestByStrategyId` stub으로 교체한다. 구현 단계에서 파일별로 실제 호출 경로를 확인해 정확한 치환 방식을 정한다.

### 2. `Sha256` → `com.kista.platform.crypto.Sha256`

`AccountNoHasher` 옆으로 그대로 이동. 패키지 선언만 변경, 로직 무변경. 호출부 2곳(`TokenService`, `TossRedisTokenStore`) import 경로만 갱신.

### 3. `TimeZones` → `com.kista.sharedkernel.TimeZones`

25개 호출부 import 경로 갱신. 도메인 클래스(`DstInfo`/`StrategyCycle`/`MarketSessionSnapshot`)가 이미 참조 중이므로 sharedkernel(OPEN, outbound-zero)로의 이동이 오히려 "도메인이 참조 가능한 공용 어휘" 전제를 명확히 한다.

### 4. `UsTradeDates` → `com.kista.sharedkernel.UsTradeDates`

4개 호출부 import 경로 갱신. 클래스 상단 주석("도메인·서비스·persistence에서 사용 금지")은 유지 — sharedkernel로 옮겨도 이 클래스가 아무 데서나 써도 된다는 뜻은 아니므로, 아래 ArchUnit 규칙으로 이 주석을 실제로 강제한다.

### 5. `com.kista.common` 패키지 소멸

4파일 이동 완료 후 삭제 직전 `grep -rn "com\.kista\.common" --exclude-dir=build --exclude-dir=.git .`로 잔존 참조 0건(살아있는 문서 갱신 포함, 위 "문서 동기화" 반영 후)을 확인한 뒤 `com.kista.common` 디렉토리(`package-info.java` 포함) 전체 삭제. `@ApplicationModule(Type.OPEN)` 선언도 함께 소멸.

## ArchUnit 규칙 변경

### 신설: `UsTradeDates` 사용처 클래스 단위 allowlist

`HexagonalArchitectureTest`에 아래 취지의 규칙 추가:

```java
@Test
@DisplayName("UsTradeDates는 4개 KIS/Toss/캘린더 어댑터에서만 사용한다 — 시간 기준 정책 allowlist 강제")
void usTradeDates_must_only_be_used_by_allowlisted_adapters() {
    ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.kista.sharedkernel..")
            .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.kis.KisTradingApi")
            .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.kis.KisPriceApi")
            .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.toss.TossPriceApi")
            .and().doNotHaveFullyQualifiedName("com.kista.market.adapter.out.persistence.calendar.MarketCalendarPersistenceAdapter")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.kista.sharedkernel.UsTradeDates");
    rule.check(classes);
}
```

클래스 단위 allowlist다 — `TossPriceApi` 내부에서 실제로는 `getClosingPrice` 메서드만 쓰지만, 메서드 단위 강제는 ArchUnit 복잡도 대비 이득이 낮아 채택하지 않는다(사용자 확인됨). 새 KIS/Toss/캘린더 어댑터가 US 거래일 변환이 필요해지면 이 allowlist에 클래스를 추가하는 것으로 확장한다.

`ClassFileImporter().importPackages("com.kista")`(기존 `HexagonalArchitectureTest.setUp()`)는 테스트 클래스도 import 대상에 포함한다(`DoNotIncludeTests` 미지정). 현재 `UsTradeDates`를 참조하는 테스트가 0곳이라 신설 규칙은 바로는 안 깨지지만, 이후 어댑터 테스트가 기대값 계산에 `UsTradeDates`를 직접 쓰면 이 규칙이 걸린다 — 규칙 주석에 이 사실을 한 줄 남긴다.

### 수정: `platform_must_not_depend_on_other_modules`

기존 규칙의 `.and(resideOutsideOfPackage("com.kista.common.."))` 예외 절 제거 — `com.kista.common`이 소멸하므로 예외 자체가 대상이 없어진다.

## 테스트 이동

- `CycleLookupsTest` → `StrategyCyclePortTest`(`com.kista.trading.application.port.output`) — 위 Mockito 낙진과 동일한 이유로 순수 Mockito mock은 default 메서드 본문을 실행하지 않는다. `mock(StrategyCyclePort.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))`로 생성하거나, `findLatestByStrategyId`만 구현한 최소 익명 구현체를 직접 만들어 그 위에서 `requireLatestByStrategyId`를 호출·검증한다(순수 Mockito mock + `findLatestByStrategyId` stub만으로는 default 메서드가 override돼 테스트가 거짓 통과/NPE로 깨진다).
- `Sha256Test` → `com.kista.platform.crypto.Sha256Test` — 내용 무변경, 패키지만 이동.
- `TimeZones`/`UsTradeDates`는 기존에 전용 단위 테스트 없음 — 신설하지 않음(스코프 밖).

## 문서 동기화

전역 grep(`grep -rn "com\.kista\.common" --exclude-dir=build --exclude-dir=.git .`)으로 확인한 살아있는 문서 전체 — `docs/superpowers/plans/*.md`는 과거 시점 기록이라 갱신하지 않음(스펙/플랜 문서는 작성 시점 스냅샷, SSOT 아님):

- `docs/agents/architecture.md`: `com.kista.common/` 패키지 절 삭제. `com.kista.sharedkernel/` 절에 `TimeZones`/`UsTradeDates` 추가, `com.kista.platform/crypto/` 절에 `Sha256` 추가. `com.kista.platform/` 절 첫 문장의 "`com.kista.common`만 허용, 그 외 `com.kista..`는 전부 금지" 서술도 예외 절 삭제에 맞춰 "outbound-zero"로 정리.
- `docs/agents/constraints.md`: "Spring Modulith 이전 중 신규 파일 배치" 문단에서 common 관련 서술 제거. "시간 기준 정책" 문단의 `UsTradeDates` allowlist 설명에 "ArchUnit(`usTradeDates_must_only_be_used_by_allowlisted_adapters`)으로 강제됨" 한 줄 추가.
- `docs/agents/docker-infra.md`: `TimeZones.KST`(`com.kista.common.TimeZones`) 표기를 `com.kista.sharedkernel.TimeZones`로 갱신.
- `README.md`: "잔존물은 `com.kista.common`(순수 유틸)뿐" 문구 제거(공용 유틸은 `sharedkernel`/`platform`으로 흡수됐다는 서술로 교체). ⚠ 같은 줄의 "10개 애그리게이트" 숫자는 이미 현재 architecture.md의 13개 모듈 구성과 불일치하는 기존 드리프트 — 이번 작업 범위 밖이라 별도로 짚어두되, common 문구를 고치는 같은 편집에서 숫자도 13으로 맞추는 것이 자연스러워 함께 고친다.
- `com.kista.platform.package-info.java`: "Spring/JPA 바인딩이 있어 com.kista.common(순수 유틸)과 분리한다" 문장 자체가 대상(common) 소멸로 무의미해짐 — "sharedkernel과 달리 Spring/JPA 바인딩을 가지는 인프라 leaf" 류로 재작성.
- `com.kista.sharedkernel.package-info.java`: 현재 "순수 값 타입(enum)만 담는다. **common/과 달리 기술 유틸이 아닌 도메인 개념**이라 별도 패키지로 분리"라고 명시돼 있어 `TimeZones`/`UsTradeDates`(기술 유틸)가 들어오면 이 문장과 정면으로 충돌한다(advisor 검토로 발견). "outbound-zero 공용 타입(도메인 어휘 enum + JDK-only 유틸)"류로 재작성 — 별도 패키지 분리 근거를 outbound-zero 전제 자체로 옮긴다.
- 모듈 경계 재구성 로드맵 메모리(`project_module_boundary_roadmap.md`): #3 완료 반영.

## 영향 범위 밖 (건드리지 않음)

- `CycleLookups.requireLatestCycle`의 예외 메시지·동작(`IllegalStateException("활성 사이클 없음: strategyId=" + strategyId)`)은 그대로 유지 — GlobalExceptionHandler 400 매핑 등 기존 계약 무변경.
- `TimeZones.KST_ID`(String 상수, `@Scheduled(zone=...)` 용) 등 기존 필드 전부 그대로 이동, 리네임 없음.
- #1/#2/#4 재작업 없음.

## 검증

순서 고정 — Mockito 낙진이 컴파일로는 안 잡히므로 trading·admin 서비스 테스트를 아키텍처 테스트 다음, 전체 스위트 이전에 반드시 별도로 확인한다:

1. `./gradlew compileJava` — import 경로 전환 컴파일 확인
2. `./gradlew test --tests 'com.kista.architecture.*'` — 신설/수정 ArchUnit 규칙 + `ModulithArchitectureTest.verify()` GREEN 확인
3. `./gradlew test --tests 'com.kista.trading.application.port.output.*'` — 이동한 `StrategyCyclePortTest`
4. `./gradlew test --tests 'com.kista.platform.crypto.*'` — 이동한 `Sha256Test`
5. `./gradlew test --tests 'com.kista.trading.application.service.*' --tests 'com.kista.admin.application.service.*' --tests 'com.kista.trading.adapter.*'` — `requireLatestByStrategyId` 낙진 대상 12개 파일이 걸린 패키지 전체(NPE 여부 확인)
6. 최종 1회 `./gradlew test` 전체 스위트
