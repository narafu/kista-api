# Spring Modulith market 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 최상위(`com.kista.domain`/`application`/`adapter`, `Type.OPEN`)에 흩어진 시장 공개 참조 데이터(공포탐욕지수 + 미국 시장 휴장일 캘린더)를 신규 `com.kista.market` Spring Modulith `CLOSED` 모듈로 이전한다.

**Architecture:** finance/notify/broker/trading 4모듈 이전과 동일 패턴 — 모듈 내부는 기존 Hexagonal 레이어(`domain/application/adapter`)를 그대로 유지하고, 최상위 패키지만 `com.kista.market`으로 옮긴다. 이번 작업은 새 동작을 추가하는 게 아니라 순수 구조 이전이라 TDD red-green이 아닌 "이동 → import 정합화 → 컴파일/테스트 그린 → 커밋" 사이클로 태스크를 구성한다. `Spring Modulith`가 모듈 경계를, 기존 `HexagonalArchitectureTest`가 모듈 내부 레이어 방향을 검증한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5

**Spec:** `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` ("착수 순서 (실측 기반, v2)" 1단계 중 market)

## Global Constraints

- **[정정, Task 2 리뷰에서 발견]** 스펙 "결합도 실측" 표의 "market: 순환 없음, backward 0" 판정은 틀렸다 — 직접 쌍(pairwise)만 확인하고 `market→notify→trading→market` 3단 전이 순환을 놓쳤다. `trading→market`(`MarketCalendarPort` 소비, 정상적인 리프 소비 — 반드시 유지)과 `notify→trading`(`TradingAlertNotifier`, 기존 확립된 설계 — 반드시 유지)은 건드리지 않고, `market→notify`(`FearGreedService.notifyError` 직접 호출) 하나만 Task 3에서 이벤트 발행/구독으로 끊어 순환을 해소한다. Task 3~4를 거친 뒤에는 다시 "순환 없음"이 참이 된다.
- `MarketIndexPrice*`/`AlpacaIndexPriceAdapter`/`MarketIndexPriceSyncScheduler`/`MarketIndexPriceSyncService`는 market이 아니라 **stats 소유**로 이미 재분류됨 — 이번 스코프에서 절대 건드리지 않는다.
- `adapter/out/alpaca/AlpacaConfig.java`/`AlpacaProperties.java`의 `alpacaRestClient` 빈은 시장 캘린더(market)와 지수가격(stats, 이번엔 안 건드림) 양쪽이 공유 중 — market 이전 시 이 두 파일은 **이동이 아니라 복제**해서 market에 자기 몫을 두고, 레거시 `adapter/out/alpaca/`에는 `AlpacaIndexPriceAdapter`가 계속 쓸 수 있도록 원본을 그대로 남긴다(stats 이전 시점에 레거시 쪽도 정리).
- 커밋 전 검토자(리뷰어 서브에이전트) 검수 필수 — 전역 CLAUDE.md 규칙, 문서 전용 변경 제외 전부 적용.
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit.

---

## Task 1: 코어(domain + application) 이전 + 프로젝트 전역 import 정합화

**Files:**
- Move: `src/main/java/com/kista/domain/model/market/{FearGreedRating,FearGreedSnapshot}.java` → `src/main/java/com/kista/market/domain/model/`
- Move: `src/main/java/com/kista/application/service/market/{FearGreedQueryService,MarketHolidayService,FearGreedService}.java` → `src/main/java/com/kista/market/application/service/`
- Move: `src/main/java/com/kista/application/usecase/{FetchFearGreedUseCase,GetFearGreedUseCase}.java` → `src/main/java/com/kista/market/application/usecase/`
- Move: `src/main/java/com/kista/application/port/output/{FearGreedSnapshotPort,CnnFearGreedPort,CryptoFearGreedPort,MarketCalendarPort,MarketHolidayStorePort,MarketCalendarRefreshPort}.java` → `src/main/java/com/kista/market/application/port/output/`
- Modify (import 경로만, 이동 안 함): `src/main/java/com/kista/trading/application/service/TradingService.java`, `src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java`, `src/main/java/com/kista/adapter/in/web/AdminTradeController.java`, `src/main/java/com/kista/application/service/admin/AdminReorderService.java`
- Move: `src/test/java/com/kista/application/service/market/{FearGreedQueryServiceTest,FearGreedServiceTest}.java` → `src/test/java/com/kista/market/application/service/`

**Interfaces:**
- Produces: `com.kista.market.domain.model.{FearGreedRating,FearGreedSnapshot}`, `com.kista.market.application.usecase.{FetchFearGreedUseCase,GetFearGreedUseCase}`, `com.kista.market.application.port.output.{FearGreedSnapshotPort,CnnFearGreedPort,CryptoFearGreedPort,MarketCalendarPort,MarketHolidayStorePort,MarketCalendarRefreshPort}` — Task 2가 어댑터에서, Task 1 외부 소비자(trading 2곳+admin 2곳)가 즉시 이 새 경로를 참조한다.

- [ ] **Step 1: 코어 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/market/domain/model
mkdir -p src/main/java/com/kista/market/application/service
mkdir -p src/main/java/com/kista/market/application/usecase
mkdir -p src/main/java/com/kista/market/application/port/output

git mv src/main/java/com/kista/domain/model/market/FearGreedRating.java src/main/java/com/kista/market/domain/model/FearGreedRating.java
git mv src/main/java/com/kista/domain/model/market/FearGreedSnapshot.java src/main/java/com/kista/market/domain/model/FearGreedSnapshot.java
rmdir src/main/java/com/kista/domain/model/market

git mv src/main/java/com/kista/application/service/market/FearGreedQueryService.java src/main/java/com/kista/market/application/service/FearGreedQueryService.java
git mv src/main/java/com/kista/application/service/market/MarketHolidayService.java src/main/java/com/kista/market/application/service/MarketHolidayService.java
git mv src/main/java/com/kista/application/service/market/FearGreedService.java src/main/java/com/kista/market/application/service/FearGreedService.java
rmdir src/main/java/com/kista/application/service/market

git mv src/main/java/com/kista/application/usecase/FetchFearGreedUseCase.java src/main/java/com/kista/market/application/usecase/FetchFearGreedUseCase.java
git mv src/main/java/com/kista/application/usecase/GetFearGreedUseCase.java src/main/java/com/kista/market/application/usecase/GetFearGreedUseCase.java

git mv src/main/java/com/kista/application/port/output/FearGreedSnapshotPort.java src/main/java/com/kista/market/application/port/output/FearGreedSnapshotPort.java
git mv src/main/java/com/kista/application/port/output/CnnFearGreedPort.java src/main/java/com/kista/market/application/port/output/CnnFearGreedPort.java
git mv src/main/java/com/kista/application/port/output/CryptoFearGreedPort.java src/main/java/com/kista/market/application/port/output/CryptoFearGreedPort.java
git mv src/main/java/com/kista/application/port/output/MarketCalendarPort.java src/main/java/com/kista/market/application/port/output/MarketCalendarPort.java
git mv src/main/java/com/kista/application/port/output/MarketHolidayStorePort.java src/main/java/com/kista/market/application/port/output/MarketHolidayStorePort.java
git mv src/main/java/com/kista/application/port/output/MarketCalendarRefreshPort.java src/main/java/com/kista/market/application/port/output/MarketCalendarRefreshPort.java

mkdir -p src/test/java/com/kista/market/application/service
git mv src/test/java/com/kista/application/service/market/FearGreedQueryServiceTest.java src/test/java/com/kista/market/application/service/FearGreedQueryServiceTest.java
git mv src/test/java/com/kista/application/service/market/FearGreedServiceTest.java src/test/java/com/kista/market/application/service/FearGreedServiceTest.java
rmdir src/test/java/com/kista/application/service/market
```

- [ ] **Step 2: 이동한 파일들의 package 선언 + 내부 상호 import 수정**

각 이동 파일의 `package` 줄과, 서로를 참조하는 import를 아래처럼 일괄 치환한다(예: `FearGreedQueryService.java`가 `FearGreedSnapshot`/`FearGreedSnapshotPort`/`GetFearGreedUseCase`를 import하는 식):

```bash
cd src/main/java/com/kista/market

# package 선언 치환
find . -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.domain\.model\.market;/package com.kista.market.domain.model;/' \
  -e 's/^package com\.kista\.application\.service\.market;/package com.kista.market.application.service;/' \
  {} +

# usecase/port 개별 파일은 package 선언이 com.kista.application.usecase / com.kista.application.port.output 이었으므로 직접 치환
sed -i '' 's/^package com\.kista\.application\.usecase;/package com.kista.market.application.usecase;/' application/usecase/*.java
sed -i '' 's/^package com\.kista\.application\.port\.output;/package com.kista.market.application.port.output;/' application/port/output/*.java

# 서로 간 import 경로 치환(이동된 6개 타입 전부)
find . -name "*.java" -exec sed -i '' \
  -e 's/com\.kista\.domain\.model\.market\./com.kista.market.domain.model./g' \
  -e 's/com\.kista\.application\.usecase\.FetchFearGreedUseCase/com.kista.market.application.usecase.FetchFearGreedUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.GetFearGreedUseCase/com.kista.market.application.usecase.GetFearGreedUseCase/g' \
  -e 's/com\.kista\.application\.port\.output\.FearGreedSnapshotPort/com.kista.market.application.port.output.FearGreedSnapshotPort/g' \
  -e 's/com\.kista\.application\.port\.output\.CnnFearGreedPort/com.kista.market.application.port.output.CnnFearGreedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.CryptoFearGreedPort/com.kista.market.application.port.output.CryptoFearGreedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketCalendarPort/com.kista.market.application.port.output.MarketCalendarPort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketHolidayStorePort/com.kista.market.application.port.output.MarketHolidayStorePort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketCalendarRefreshPort/com.kista.market.application.port.output.MarketCalendarRefreshPort/g' \
  {} +

cd -
```

이동한 테스트 파일(`FearGreedQueryServiceTest.java`/`FearGreedServiceTest.java`)의 `package`/`import` 줄도 동일 패턴으로 수정:

```bash
find src/test/java/com/kista/market -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.application\.service\.market;/package com.kista.market.application.service;/' \
  -e 's/com\.kista\.domain\.model\.market\./com.kista.market.domain.model./g' \
  -e 's/com\.kista\.application\.usecase\.FetchFearGreedUseCase/com.kista.market.application.usecase.FetchFearGreedUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.GetFearGreedUseCase/com.kista.market.application.usecase.GetFearGreedUseCase/g' \
  -e 's/com\.kista\.application\.port\.output\.FearGreedSnapshotPort/com.kista.market.application.port.output.FearGreedSnapshotPort/g' \
  -e 's/com\.kista\.application\.port\.output\.CnnFearGreedPort/com.kista.market.application.port.output.CnnFearGreedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.CryptoFearGreedPort/com.kista.market.application.port.output.CryptoFearGreedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketCalendarPort/com.kista.market.application.port.output.MarketCalendarPort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketHolidayStorePort/com.kista.market.application.port.output.MarketHolidayStorePort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketCalendarRefreshPort/com.kista.market.application.port.output.MarketCalendarRefreshPort/g' \
  {} +
```

- [ ] **Step 3: 프로젝트 전역 소비자 4곳 import 경로 수정 (아직 옮기지 않은 어댑터 포함, 컴파일 위해 필요)**

이 시점엔 `market`의 어댑터(controller/scheduler/adapter/persistence, Task 2 대상)가 아직 레거시 위치에 남아있어 `MarketCalendarPort` 등을 구현하는 클래스들도 import 수정이 필요하다. 대상은 `AlpacaCalendarAdapter`(MarketCalendarRefreshPort/MarketHolidayStorePort 구현), `MarketCalendarPersistenceAdapter`(MarketHolidayStorePort/MarketCalendarPort 구현), `CnnFearGreedAdapter`/`CryptoFearGreedAdapter`(CnnFearGreedPort/CryptoFearGreedPort 구현), `FearGreedSnapshotPersistenceAdapter`(FearGreedSnapshotPort 구현), `FearGreedController`/`MarketHolidayController`(usecase 참조), `FearGreedScheduler`/`MarketCalendarRefreshScheduler`(usecase/port 참조) — 이 파일들은 Task 2에서 물리 이동하지만, Task 1을 단독으로 컴파일 가능하게 만들려면 지금 import만 먼저 고쳐도 되고, Task 2로 미뤄도 된다. **이번 태스크는 이동 안 하는 순수 외부 소비자(trading 2곳 + admin 2곳)만 고치고, 나머지 레거시 어댑터 import 수정은 Task 2에서 물리 이동과 함께 처리한다** — 그래야 이번 태스크 하나만 봐도 diff가 "코어 이동" 하나의 책임에 집중된다.

```bash
sed -i '' 's/com\.kista\.application\.port\.output\.MarketCalendarPort/com.kista.market.application.port.output.MarketCalendarPort/g' \
  src/main/java/com/kista/trading/application/service/TradingService.java \
  src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java \
  src/main/java/com/kista/adapter/in/web/AdminTradeController.java \
  src/main/java/com/kista/application/service/admin/AdminReorderService.java
```

- [ ] **Step 4: 컴파일 확인 (완전한 컴파일은 Task 2 이후에나 가능 — 이번엔 에러 목록만 확인)**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|FAILED"
```

Expected: `market` 어댑터 구현체(아직 레거시 위치)들이 옛 import를 그대로 갖고 있어 `cannot find symbol` 에러가 다수 발생 — 정상이다. 에러 목록에 위 Step 1~3에서 옮긴 6개 포트/2개 usecase/2개 도메인 타입 외의 것이 섞여 있으면(즉 이번 스코프 밖 타입이 깨졌으면) 즉시 원인 확인.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): market 모듈 코어(domain+application) 이전

FearGreedRating/FearGreedSnapshot, FearGreedQueryService/MarketHolidayService/
FearGreedService, Fetch·GetFearGreedUseCase, 6개 output port를
com.kista.market으로 이전. 어댑터 레이어는 Task 2에서 이어서 이전 —
이 시점엔 컴파일 에러 존재가 정상(어댑터가 아직 레거시 경로 참조).

EOF
)"
```

---

## Task 2: 어댑터(in/out) 물리 이전 + 전체 컴파일 그린화

**Files:**
- Move: `src/main/java/com/kista/adapter/in/web/{FearGreedController,MarketHolidayController}.java` → `src/main/java/com/kista/market/adapter/in/web/`
- Move: `src/main/java/com/kista/adapter/in/web/dto/FearGreedResponse.java` → `src/main/java/com/kista/market/adapter/in/web/dto/`
- Move: `src/main/java/com/kista/adapter/in/schedule/{FearGreedScheduler,MarketCalendarRefreshScheduler}.java` → `src/main/java/com/kista/market/adapter/in/schedule/`
- Move: `src/main/java/com/kista/adapter/out/feargreed/{FearGreedConfig,CnnFearGreedAdapter,CryptoFearGreedAdapter}.java` → `src/main/java/com/kista/market/adapter/out/feargreed/`
- Move: `src/main/java/com/kista/adapter/out/persistence/calendar/{UsMarketHolidayJpaRepository,UsMarketHolidayEntity,MarketCalendarPersistenceAdapter}.java` → `src/main/java/com/kista/market/adapter/out/persistence/calendar/`
- Move: `src/main/java/com/kista/adapter/out/persistence/feargreed/{FearGreedSnapshotJpaRepository,FearGreedSnapshotEntity,FearGreedSnapshotPersistenceAdapter}.java` → `src/main/java/com/kista/market/adapter/out/persistence/feargreed/`
- Copy(이동 아님, 원본 유지): `src/main/java/com/kista/adapter/out/alpaca/AlpacaCalendarAdapter.java` → `src/main/java/com/kista/market/adapter/out/alpaca/AlpacaCalendarAdapter.java`(원본 삭제), `AlpacaConfig.java`/`AlpacaProperties.java` → 같은 위치에 **복제**(원본은 `AlpacaIndexPriceAdapter`를 위해 레거시에 그대로 둠)
- Move 대응 테스트 9개(아래 Step 참고)

**Interfaces:**
- Consumes: Task 1이 만든 `com.kista.market.application.{usecase,port.output}.*`, `com.kista.market.domain.model.*`
- Produces: `com.kista.market.adapter.*` 전체 — Task 5의 NamedInterface 선언 대상은 domain/port뿐이라 adapter는 외부 공개 안 됨(internal 유지)

- [ ] **Step 1: 어댑터 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/market/adapter/in/web/dto
mkdir -p src/main/java/com/kista/market/adapter/in/schedule
mkdir -p src/main/java/com/kista/market/adapter/out/feargreed
mkdir -p src/main/java/com/kista/market/adapter/out/alpaca
mkdir -p src/main/java/com/kista/market/adapter/out/persistence/calendar
mkdir -p src/main/java/com/kista/market/adapter/out/persistence/feargreed

git mv src/main/java/com/kista/adapter/in/web/FearGreedController.java src/main/java/com/kista/market/adapter/in/web/FearGreedController.java
git mv src/main/java/com/kista/adapter/in/web/MarketHolidayController.java src/main/java/com/kista/market/adapter/in/web/MarketHolidayController.java
git mv src/main/java/com/kista/adapter/in/web/dto/FearGreedResponse.java src/main/java/com/kista/market/adapter/in/web/dto/FearGreedResponse.java

git mv src/main/java/com/kista/adapter/in/schedule/FearGreedScheduler.java src/main/java/com/kista/market/adapter/in/schedule/FearGreedScheduler.java
git mv src/main/java/com/kista/adapter/in/schedule/MarketCalendarRefreshScheduler.java src/main/java/com/kista/market/adapter/in/schedule/MarketCalendarRefreshScheduler.java

git mv src/main/java/com/kista/adapter/out/feargreed/FearGreedConfig.java src/main/java/com/kista/market/adapter/out/feargreed/FearGreedConfig.java
git mv src/main/java/com/kista/adapter/out/feargreed/CnnFearGreedAdapter.java src/main/java/com/kista/market/adapter/out/feargreed/CnnFearGreedAdapter.java
git mv src/main/java/com/kista/adapter/out/feargreed/CryptoFearGreedAdapter.java src/main/java/com/kista/market/adapter/out/feargreed/CryptoFearGreedAdapter.java
rmdir src/main/java/com/kista/adapter/out/feargreed

git mv src/main/java/com/kista/adapter/out/alpaca/AlpacaCalendarAdapter.java src/main/java/com/kista/market/adapter/out/alpaca/AlpacaCalendarAdapter.java
cp src/main/java/com/kista/adapter/out/alpaca/AlpacaConfig.java src/main/java/com/kista/market/adapter/out/alpaca/AlpacaConfig.java
cp src/main/java/com/kista/adapter/out/alpaca/AlpacaProperties.java src/main/java/com/kista/market/adapter/out/alpaca/AlpacaProperties.java
git add src/main/java/com/kista/market/adapter/out/alpaca/AlpacaConfig.java src/main/java/com/kista/market/adapter/out/alpaca/AlpacaProperties.java

git mv src/main/java/com/kista/adapter/out/persistence/calendar/UsMarketHolidayJpaRepository.java src/main/java/com/kista/market/adapter/out/persistence/calendar/UsMarketHolidayJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/calendar/UsMarketHolidayEntity.java src/main/java/com/kista/market/adapter/out/persistence/calendar/UsMarketHolidayEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapter.java src/main/java/com/kista/market/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapter.java
rmdir src/main/java/com/kista/adapter/out/persistence/calendar

git mv src/main/java/com/kista/adapter/out/persistence/feargreed/FearGreedSnapshotJpaRepository.java src/main/java/com/kista/market/adapter/out/persistence/feargreed/FearGreedSnapshotJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/feargreed/FearGreedSnapshotEntity.java src/main/java/com/kista/market/adapter/out/persistence/feargreed/FearGreedSnapshotEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/feargreed/FearGreedSnapshotPersistenceAdapter.java src/main/java/com/kista/market/adapter/out/persistence/feargreed/FearGreedSnapshotPersistenceAdapter.java
rmdir src/main/java/com/kista/adapter/out/persistence/feargreed
```

- [ ] **Step 2: 테스트 파일 물리 이동**

```bash
mkdir -p src/test/java/com/kista/market/adapter/in/web
mkdir -p src/test/java/com/kista/market/adapter/in/schedule
mkdir -p src/test/java/com/kista/market/adapter/out/feargreed
mkdir -p src/test/java/com/kista/market/adapter/out/alpaca
mkdir -p src/test/java/com/kista/market/adapter/out/persistence/calendar
mkdir -p src/test/java/com/kista/market/adapter/out/persistence/feargreed

git mv src/test/java/com/kista/adapter/in/web/FearGreedControllerTest.java src/test/java/com/kista/market/adapter/in/web/FearGreedControllerTest.java
git mv src/test/java/com/kista/adapter/in/web/MarketHolidayControllerTest.java src/test/java/com/kista/market/adapter/in/web/MarketHolidayControllerTest.java
git mv src/test/java/com/kista/adapter/in/schedule/MarketCalendarRefreshSchedulerTest.java src/test/java/com/kista/market/adapter/in/schedule/MarketCalendarRefreshSchedulerTest.java
git mv src/test/java/com/kista/adapter/out/feargreed/CnnFearGreedAdapterTest.java src/test/java/com/kista/market/adapter/out/feargreed/CnnFearGreedAdapterTest.java
git mv src/test/java/com/kista/adapter/out/feargreed/CryptoFearGreedAdapterTest.java src/test/java/com/kista/market/adapter/out/feargreed/CryptoFearGreedAdapterTest.java
git mv src/test/java/com/kista/adapter/out/feargreed/FearGreedConfigTest.java src/test/java/com/kista/market/adapter/out/feargreed/FearGreedConfigTest.java
rmdir src/test/java/com/kista/adapter/out/feargreed
git mv src/test/java/com/kista/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapterTest.java src/test/java/com/kista/market/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/persistence/calendar
git mv src/test/java/com/kista/adapter/out/persistence/feargreed/FearGreedSnapshotPersistenceAdapterTest.java src/test/java/com/kista/market/adapter/out/persistence/feargreed/FearGreedSnapshotPersistenceAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/persistence/feargreed
cp src/test/java/com/kista/adapter/out/alpaca/AlpacaCalendarAdapterTest.java src/test/java/com/kista/market/adapter/out/alpaca/AlpacaCalendarAdapterTest.java
git rm src/test/java/com/kista/adapter/out/alpaca/AlpacaCalendarAdapterTest.java
cp src/test/java/com/kista/adapter/out/alpaca/AlpacaConfigTest.java src/test/java/com/kista/market/adapter/out/alpaca/AlpacaConfigTest.java
git add src/test/java/com/kista/market/adapter/out/alpaca/*.java
```

주의: `AlpacaCalendarAdapterTest.java`는 market 전용(Alpaca 캘린더만 테스트)이라 이동, `AlpacaConfigTest.java`는 `AlpacaConfig`가 market/레거시 양쪽에 복제되므로 market 쪽에 복사본을 만들고 **레거시 원본 테스트도 그대로 유지**(레거시 `AlpacaIndexPriceAdapter`가 레거시 `AlpacaConfig`를 계속 쓰므로).

- [ ] **Step 3: package 선언 + import 일괄 치환**

```bash
find src/main/java/com/kista/market/adapter src/test/java/com/kista/market/adapter -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.adapter\.in\.web\.dto;/package com.kista.market.adapter.in.web.dto;/' \
  -e 's/^package com\.kista\.adapter\.in\.web;/package com.kista.market.adapter.in.web;/' \
  -e 's/^package com\.kista\.adapter\.in\.schedule;/package com.kista.market.adapter.in.schedule;/' \
  -e 's/^package com\.kista\.adapter\.out\.feargreed;/package com.kista.market.adapter.out.feargreed;/' \
  -e 's/^package com\.kista\.adapter\.out\.alpaca;/package com.kista.market.adapter.out.alpaca;/' \
  -e 's/^package com\.kista\.adapter\.out\.persistence\.calendar;/package com.kista.market.adapter.out.persistence.calendar;/' \
  -e 's/^package com\.kista\.adapter\.out\.persistence\.feargreed;/package com.kista.market.adapter.out.persistence.feargreed;/' \
  -e 's/com\.kista\.domain\.model\.market\./com.kista.market.domain.model./g' \
  -e 's/com\.kista\.application\.usecase\.FetchFearGreedUseCase/com.kista.market.application.usecase.FetchFearGreedUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.GetFearGreedUseCase/com.kista.market.application.usecase.GetFearGreedUseCase/g' \
  -e 's/com\.kista\.application\.port\.output\.FearGreedSnapshotPort/com.kista.market.application.port.output.FearGreedSnapshotPort/g' \
  -e 's/com\.kista\.application\.port\.output\.CnnFearGreedPort/com.kista.market.application.port.output.CnnFearGreedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.CryptoFearGreedPort/com.kista.market.application.port.output.CryptoFearGreedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketCalendarPort/com.kista.market.application.port.output.MarketCalendarPort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketHolidayStorePort/com.kista.market.application.port.output.MarketHolidayStorePort/g' \
  -e 's/com\.kista\.application\.port\.output\.MarketCalendarRefreshPort/com.kista.market.application.port.output.MarketCalendarRefreshPort/g' \
  -e 's/com\.kista\.adapter\.in\.web\.dto\.FearGreedResponse/com.kista.market.adapter.in.web.dto.FearGreedResponse/g' \
  {} +
```

`AlpacaCalendarAdapter.java`(market 이동본)는 여전히 `com.kista.adapter.out.alpaca.AlpacaConfig`/`AlpacaProperties`를 참조하는데, 이제 같은 새 패키지(`com.kista.market.adapter.out.alpaca`) 안에 복제본이 있으므로 별도 import 불필요(같은 패키지) — 다만 원래 파일에 `import com.kista.adapter.out.alpaca.AlpacaProperties;` 같은 명시적 import가 있었다면 제거해야 한다:

```bash
sed -i '' '/^import com\.kista\.adapter\.out\.alpaca\.AlpacaProperties;/d' src/main/java/com/kista/market/adapter/out/alpaca/AlpacaCalendarAdapter.java
sed -i '' '/^import com\.kista\.adapter\.out\.alpaca\.AlpacaProperties;/d' src/test/java/com/kista/market/adapter/out/alpaca/AlpacaCalendarAdapterTest.java
```

레거시 `AlpacaIndexPriceAdapter.java`는 그대로 두되(같은 패키지 `com.kista.adapter.out.alpaca`에 `AlpacaConfig`/`AlpacaProperties` 원본이 남아있으므로 수정 불필요), 이 파일의 존재를 이 태스크의 `git status`에서 "unmodified"로 반드시 확인한다.

- [ ] **Step 4: 전체 컴파일 + 대상 테스트 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```

Expected: 에러 없음(전체 컴파일 성공). 실패 시 남은 옛 경로 import를 `git grep -n "com\.kista\.domain\.model\.market\|com\.kista\.application\.service\.market\|com\.kista\.adapter\.in\.web\.FearGreedController\|com\.kista\.adapter\.in\.web\.MarketHolidayController\|com\.kista\.adapter\.in\.schedule\.FearGreedScheduler\|com\.kista\.adapter\.in\.schedule\.MarketCalendarRefreshScheduler\|com\.kista\.adapter\.out\.feargreed\|com\.kista\.adapter\.out\.persistence\.calendar\|com\.kista\.adapter\.out\.persistence\.feargreed"`로 재검색.

```bash
./gradlew test --tests 'com.kista.market.*' 2>&1 | tail -50
```

Expected: BUILD SUCCESSFUL, 12개 테스트 클래스 전부 통과.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): market 모듈 어댑터 이전 + 전체 컴파일 정합화

FearGreedController/MarketHolidayController, FearGreedScheduler/
MarketCalendarRefreshScheduler, feargreed·alpaca(캘린더분)·persistence
어댑터 3종 세트를 com.kista.market으로 이전. AlpacaConfig/Properties는
AlpacaIndexPriceAdapter(레거시, stats 소유 예정)가 계속 써야 해서
이동이 아닌 복제로 처리. 전체 컴파일·market 테스트 12개 그린 확인.

EOF
)"
```

---

## Task 3: market→notify 직접 호출을 이벤트 발행/구독으로 전환 (순환 해소)

> **삽입 배경**: Task 2 리뷰(sonnet)에서 `ModulithArchitectureTest`를 실측 실행해본 결과 `Cycle detected: market -> notify -> trading -> market`가 실제로 존재함이 확인됐다. `market→notify`(`FearGreedService.notifyPort.notifyError(e)`)와 `notify→trading`(`TradingAlertNotifier`가 trading 이벤트 구독, 기존 설계)과 `trading→market`(`TradingService`/`VrCycleRolloverService`가 `MarketCalendarPort` 소비, 정상적인 리프 소비) 세 변이 합쳐져 3단 전이 순환을 만든다. 스펙 문서(`2026-08-31-legacy-module-catalog-design.md`)의 "market: 순환 없음" 판정은 직접 쌍(pairwise)만 확인하고 이런 전이 순환을 놓친 것으로 확인됨 — Task 5(모듈 선언)만으로는 이 순환이 사라지지 않는다(NamedInterface 선언은 노출 여부만 결정, 순환 자체는 별개 축). `trading→market`(정상적인 리프 소비, 다른 여러 모듈이 같은 방식으로 market을 쓰게 될 예정이라 절대 건드리면 안 됨)과 `notify→trading`(기존에 이미 확립된, 되돌리면 안 되는 설계)은 그대로 두고, 유일하게 새로 생긴 `market→notify` 엣지 하나만 끊는다 — 기존 `broker→trading→notify→broker` 순환을 없앨 때 쓴 것과 동일한 이벤트 발행/구독 패턴(`TradingAlertNotifier` 참고)을 그대로 재사용한다.

**Files:**
- Create: `src/main/java/com/kista/market/application/event/FearGreedFetchFailedEvent.java`
- Create: `src/main/java/com/kista/market/application/event/package-info.java`
- Modify: `src/main/java/com/kista/market/application/service/FearGreedService.java`
- Create: `src/main/java/com/kista/notify/adapter/out/gateway/MarketAlertNotifier.java`
- Test: `src/test/java/com/kista/market/application/service/FearGreedServiceTest.java` (기존 테스트가 `NotifyPort` mock을 쓰고 있다면 `ApplicationEventPublisher` mock으로 교체)

**Interfaces:**
- Consumes: 기존 `com.kista.notify.application.port.output.NotifyPort.notifyError(Exception)` (notify 쪽에서만 계속 씀)
- Produces: `com.kista.market.application.event.FearGreedFetchFailedEvent(String message)` — "event" NamedInterface로 공개, notify가 구독

- [ ] **Step 1: 이벤트 record + NamedInterface 작성**

```java
// src/main/java/com/kista/market/application/event/FearGreedFetchFailedEvent.java
package com.kista.market.application.event;

// 공포탐욕지수(CNN/CRYPTO) 수집 실패 알림 — 관리자 전용(NotifyPort.notifyError), userId 없음.
// Exception 자체는 EPR 직렬화 부적합(스택트레이스·cause 체인)해 message만 담는다 — 소비처(notify)가
// e.getMessage()만 쓴다는 걸 FearGreedService의 기존 log.error 호출로 확인했다.
public record FearGreedFetchFailedEvent(String message) {}
```

```java
// src/main/java/com/kista/market/application/event/package-info.java
// market 모듈의 공개 계약 일부 — FearGreedFetchFailedEvent. notify 모듈이 @TransactionalEventListener로
// 구독한다(CLOSED↔CLOSED 모듈 간 이벤트 교차, trading.application.event와 동일 패턴). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.market.application.event;
```

- [ ] **Step 2: FearGreedService를 이벤트 발행으로 전환**

`private final NotifyPort notifyPort;` 필드와 `import com.kista.notify.application.port.output.NotifyPort;`를 제거하고 `ApplicationEventPublisher`로 교체:

```java
package com.kista.market.application.service;

import com.kista.market.domain.model.FearGreedSnapshot;
import com.kista.market.application.event.FearGreedFetchFailedEvent;
import com.kista.market.application.usecase.FetchFearGreedUseCase;
import com.kista.market.application.port.output.CnnFearGreedPort;
import com.kista.market.application.port.output.CryptoFearGreedPort;
import com.kista.market.application.port.output.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
class FearGreedService implements FetchFearGreedUseCase {

    private final CryptoFearGreedPort cryptoFearGreedPort;
    private final CnnFearGreedPort cnnFearGreedPort;
    private final FearGreedSnapshotPort fearGreedSnapshotPort;
    private final ApplicationEventPublisher eventPublisher;

    private static final String SOURCE_CRYPTO = "CRYPTO";
    private static final String SOURCE_CNN    = "CNN";

    @Override
    public void fetchAndSave(Instant snapshotDate) {
        // CRYPTO와 CNN을 독립 처리 — 한쪽 실패가 다른쪽 저장을 롤백하지 않도록
        try {
            CryptoFearGreedPort.CryptoFearGreedData crypto = cryptoFearGreedPort.fetch();
            fearGreedSnapshotPort.save(FearGreedSnapshot.of(SOURCE_CRYPTO, snapshotDate, crypto.value(), crypto.rating()));
            log.info("CRYPTO 공포탐욕지수 저장 (snapshotDate={}, value={}, rating={})", snapshotDate, crypto.value(), crypto.rating());
        } catch (Exception e) {
            log.error("CRYPTO 공포탐욕지수 수집 실패: {}", e.getMessage(), e);
            eventPublisher.publishEvent(new FearGreedFetchFailedEvent(e.getMessage()));
        }

        try {
            CnnFearGreedPort.CnnFearGreedData cnn = cnnFearGreedPort.fetch();
            fearGreedSnapshotPort.save(FearGreedSnapshot.of(SOURCE_CNN, snapshotDate, cnn.value(), cnn.rating()));
            log.info("CNN 공포탐욕지수 저장 (snapshotDate={}, value={}, rating={})", snapshotDate, cnn.value(), cnn.rating());
        } catch (Exception e) {
            log.error("CNN 공포탐욕지수 수집 실패: {}", e.getMessage(), e);
            eventPublisher.publishEvent(new FearGreedFetchFailedEvent(e.getMessage()));
        }
    }
}
```

- [ ] **Step 3: notify에 리스너 추가 (TradingAlertNotifier와 동일 패턴)**

```java
// src/main/java/com/kista/notify/adapter/out/gateway/MarketAlertNotifier.java
package com.kista.notify.adapter.out.gateway;

import com.kista.market.application.event.FearGreedFetchFailedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// market이 발행하는 공포탐욕지수 수집 실패 이벤트를 구독해 기존 NotifyPort.notifyError를 그대로 호출한다.
// FearGreedService.fetchAndSave()에 @Transactional이 없어 phase 미지정 + fallbackExecution=true로
// 트랜잭션이 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다(TradingAlertNotifier와 동일 이유).
@Component
@RequiredArgsConstructor
public class MarketAlertNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onFearGreedFetchFailed(FearGreedFetchFailedEvent event) {
        notifyPort.notifyError(new RuntimeException(event.message()));
    }
}
```

- [ ] **Step 4: 기존 테스트 수정 + 신규 테스트 추가**

`FearGreedServiceTest.java`가 `NotifyPort` mock으로 `verify(notifyPort).notifyError(any())`를 검증하고 있었다면, `ApplicationEventPublisher` mock으로 바꾸고 `verify(eventPublisher).publishEvent(new FearGreedFetchFailedEvent(...))` 형태로 수정한다(정확한 기존 테스트 구조는 파일을 읽고 맞춰라 — 이 계획엔 원본 테스트 코드가 없으니 기존 assertion 스타일을 그대로 유지하며 mock 대상만 교체).

`MarketAlertNotifierTest.java`(신규, `src/test/java/com/kista/notify/adapter/out/gateway/`) — `TradingAlertNotifierTest.java`가 있다면 그 구조를 참고해 유사하게 작성: `FearGreedFetchFailedEvent`를 리스너에 직접 전달하고 `NotifyPort.notifyError`가 올바른 메시지로 호출되는지 검증.

- [ ] **Step 5: ArchUnit으로 순환 해소 확인**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest' 2>&1 | tail -60
```

Expected: 이전에 있던 `Cycle detected: market -> notify -> trading -> market` 메시지가 더 이상 나오지 않는다. (NamedInterface 미선언으로 인한 "non-exposed type" 경고는 이번 태스크 스코프가 아니므로 Task 5에서 해소된다 — 이 시점엔 여전히 나올 수 있다.)

- [ ] **Step 6: 전체 컴파일 + market/notify 관련 테스트 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.market.*' --tests 'com.kista.notify.*' 2>&1 | tail -60
```

Expected: 에러 없음, 전부 통과.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix(modulith): market→notify 직접 호출 이벤트 전환 — 3단 순환 해소

FearGreedService.notifyError() 직접 호출을 FearGreedFetchFailedEvent
발행으로 전환, notify가 MarketAlertNotifier로 구독. market→notify→
trading→market 순환(Task 2 리뷰에서 실측 발견)을 broker-trading-notify
디커플링과 동일한 이벤트 기반 패턴으로 해소.

EOF
)"
```

---

## Task 4: MarketHolidayController의 trading.DstInfo 직접 참조 제거 (2번째 순환 해소)

> **삽입 배경**: Task 3 완료 후 재실행한 `ModulithArchitectureTest`에서 두 번째 순환이 발견됐다 — `market → trading`(`MarketHolidayController`가 `trading.domain.model.DstInfo`를 직접 import해 `GET /api/market/session` 응답용 세션 판정에 씀) + 기존 `trading → market`(`MarketCalendarPort` 소비, 정상 설계) = 직접 2단 순환. `git show 496ce87e`로 확인한 결과 이 `DstInfo` 참조는 Task 2가 만든 게 아니라 레거시 `MarketHolidayController`가 OPEN 패키지였을 때부터 갖고 있던 결합이 market이 CLOSED되며 드러난 것 — Task 1/2/3 누구의 실수도 아니다.
>
> `trading→market`(MarketCalendarPort)은 정상적인 리프 소비라 절대 안 건드린다. `DstInfo`는 record 전체(4개 필드: isDst/orderAt/postClose/marketOpen)와 스케쥴러 정밀 시각 계산까지 포함한 trading 고유 도메인 개념(constraints.md에 "거래일 경계 시각 SSOT"로 명시)이라, 통째로 market과 공유 승격하는 건 trading의 안정된 공개 표면을 과하게 넓힌다. 반면 `MarketHolidayController`가 실제로 쓰는 건 `currentSession()`(DIRECT/BLOCKED, 요일+시각 기준)과 `isDst()` 딱 두 값뿐이고 호출 지점도 1곳뿐이다 — broker가 `Direction`/`OrderType`을 자기 소유로 복제했던 것과 정확히 같은 규모·같은 패턴의 문제이므로 동일하게 처리한다: market이 필요한 만큼만 자체 복제한다.

**Files:**
- Create: `src/main/java/com/kista/market/domain/model/MarketSessionSnapshot.java`
- Modify: `src/main/java/com/kista/market/adapter/in/web/MarketHolidayController.java`
- Test: `src/test/java/com/kista/market/domain/model/MarketSessionSnapshotTest.java`

**Interfaces:**
- Produces: `com.kista.market.domain.model.MarketSessionSnapshot`(record, `isDst():boolean`, `session():MarketSessionSnapshot.MarketSession`), nested enum `MarketSessionSnapshot.MarketSession{DIRECT,BLOCKED}` — market 자체 소유, `com.kista.trading.domain.model.DstInfo`와 값 집합·계산 로직만 동일한 별도 소유(trading 원본과 마찬가지로 "SSOT는 사람이 양쪽 다 고쳐야 유지됨" — 자동 동기화 장치 없음, DST 전환 규칙 자체가 안정적이라 실용적으로 허용 가능한 트레이드오프)

- [ ] **Step 1: 자체 소유 타입 작성**

```java
// src/main/java/com/kista/market/domain/model/MarketSessionSnapshot.java
package com.kista.market.domain.model;

import com.kista.common.TimeZones;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

// 현재 미국 시장 세션(수동 실행 가능 여부) 계산 — trading.domain.model.DstInfo.currentSession()/isDst()의
// 서브셋을 자체 소유로 복제(모듈 경계상 공유 불가, market↔trading 순환 방지 — broker의 Direction/OrderType과
// 동일 패턴). DST 판정·시각 상수는 DstInfo와 반드시 동기화 유지 — 자동 동기화 장치 없음, 사람이 양쪽 다 고쳐야 함.
public record MarketSessionSnapshot(boolean isDst, MarketSession session) {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // 수동 실행 시 주문 가능 시간대 (trading.DstInfo.MarketSession과 값 집합 동일)
    public enum MarketSession {
        DIRECT,  // 프리마켓+정규장: 주문 가능 (DST: 17:00~05:00, 비DST: 18:00~06:00)
        BLOCKED  // 장마감 후~프리마켓 전: 주문 불가 (DST: 05:00~17:00, 비DST: 06:00~18:00)
    }

    private static LocalTime marketCloseTime(boolean isDst)    { return isDst ? LocalTime.of(5, 0)  : LocalTime.of(6, 0); }
    private static LocalTime premarketStartTime(boolean isDst) { return isDst ? LocalTime.of(17, 0) : LocalTime.of(18, 0); }

    public static MarketSessionSnapshot now() {
        return at(ZonedDateTime.now(TimeZones.KST));
    }

    // 시각 주입식 판단 — 테스트 및 now() 공용
    static MarketSessionSnapshot at(ZonedDateTime nowKst) {
        boolean isDst = NY.getRules().isDaylightSavings(nowKst.toInstant());
        DayOfWeek day = nowKst.getDayOfWeek();
        LocalTime time = nowKst.toLocalTime();
        MarketSession session;
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            session = MarketSession.BLOCKED;
        } else if (!time.isBefore(marketCloseTime(isDst)) && time.isBefore(premarketStartTime(isDst))) {
            session = MarketSession.BLOCKED;
        } else {
            session = MarketSession.DIRECT;
        }
        return new MarketSessionSnapshot(isDst, session);
    }
}
```

- [ ] **Step 2: MarketHolidayController 수정**

`import com.kista.trading.domain.model.DstInfo;`를 제거하고 `import com.kista.market.domain.model.MarketSessionSnapshot;`로 교체, `getSession()` 메서드 본문을 다음으로 교체:

```java
    @GetMapping("/session")
    public MarketSessionResponse getSession() {
        MarketSessionSnapshot snapshot = MarketSessionSnapshot.now();
        return new MarketSessionResponse(snapshot.session().name(), snapshot.isDst());
    }
```

(다른 메서드/import는 그대로 둔다 — `MarketSessionResponse`/`TossCandleResponse`는 레거시 `com.kista.adapter.in.web.dto` 패키지에 계속 있어도 무방하다: 그 패키지는 `Type.OPEN`이라 순환 검증 대상이 아니다. 이번 태스크 스코프 밖이니 옮기지 마라.)

- [ ] **Step 3: 테스트 작성**

`trading`의 `DstInfoTest.java`(있다면)의 시각 주입식 테스트 스타일을 참고해서, DST/비DST·평일/주말·경계 시각(marketClose·premarketStart 직전/직후) 케이스를 `at(ZonedDateTime)`으로 주입해 검증하는 테스트를 작성해라. 최소 4케이스: 평일 DST DIRECT, 평일 DST BLOCKED(장마감 직후), 평일 비DST 경계, 주말(요일 무관 BLOCKED).

- [ ] **Step 4: ArchUnit으로 이번 순환도 해소됐는지 확인**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest' 2>&1 | tail -80
```

Expected: `market → trading` 순환 메시지가 사라진다. (여전히 NamedInterface 미선언 관련 경고는 남을 수 있다 — Task 5 스코프.)

- [ ] **Step 5: 전체 컴파일 + market 테스트 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.market.*' 2>&1 | tail -60
```

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix(modulith): market→trading DstInfo 직접 참조 제거 — 2번째 순환 해소

MarketHolidayController가 trading.domain.model.DstInfo를 직접 쓰던
결합(레거시 시절부터 있던 것, market CLOSED 전환으로 드러남)을
market 자체 소유 MarketSessionSnapshot으로 대체. broker의
Direction/OrderType 복제 패턴과 동일 — trading→market(MarketCalendarPort,
정상 리프 소비)은 그대로 유지.

EOF
)"
```

---

## Task 5: 모듈 선언(CLOSED + NamedInterface) + ArchUnit 검증

**Files:**
- Create: `src/main/java/com/kista/market/package-info.java`
- Create: `src/main/java/com/kista/market/domain/model/package-info.java`
- Create: `src/main/java/com/kista/market/application/port/output/package-info.java`

**Interfaces:**
- Consumes: Task 1/2에서 이전된 전체 `com.kista.market.*`, Task 3에서 추가된 `com.kista.market.application.event`(이미 "event" NamedInterface로 선언됨)
- Produces: `"domain"`, `"port"` 2개 NamedInterface 신규 선언(+ Task 3이 이미 선언한 "event" 포함 총 3개) — 외부(trading 2곳, admin 후보 2곳)가 "domain"/"port" 이름으로 `com.kista.market.domain.model`/`com.kista.market.application.port.output`을, notify가 "event" 이름으로 `com.kista.market.application.event`를 참조 가능해짐. `application.service`/`adapter.*`는 비공개(internal) 유지.

- [ ] **Step 1: package-info 3개 작성**

```java
// src/main/java/com/kista/market/package-info.java
// market 애그리게이트(공포탐욕지수+미국 시장 휴장일 캘린더) 모듈 — domain.model·application.port.output·application.event만 공개 계약, application.service·adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.market;
```

```java
// src/main/java/com/kista/market/domain/model/package-info.java
// market 모듈의 공개 계약 일부 — 불변 값 객체(record/enum). "domain" 이름으로 공개된다(포트는 별도 "port" NamedInterface로 분리 공개).
@org.springframework.modulith.NamedInterface("domain")
package com.kista.market.domain.model;
```

```java
// src/main/java/com/kista/market/application/port/output/package-info.java
// market 모듈의 공개 계약 일부 — *Port 접미사 출력 포트 인터페이스. "port" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("port")
package com.kista.market.application.port.output;
```

- [ ] **Step 2: ArchUnit 검증**

```bash
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | tail -80
```

Expected: `ModulithArchitectureTest`(`ApplicationModules.verify()`)와 `HexagonalArchitectureTest` 전부 통과. `ModulithArchitectureTest`가 실패하면 순환 위반 메시지에 어떤 모듈-모듈 참조인지 나오므로, 스펙의 "결합도 실측" 표(market: forward만, backward 0)와 실제로 다른 참조가 있었는지 확인 — 있었다면 이 세션의 grep 조사가 놓친 케이스이므로 즉시 보고하고 계획을 멈춘다(추측으로 고치지 않는다).

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): market 모듈 선언 — CLOSED + domain·port 2개 Named Interface 공개

ApplicationModules.verify()·HexagonalArchitectureTest 그린 확인.

EOF
)"
```

---

## Task 6: 문서 갱신 + 전체 테스트 스위트 최종 검증

**Files:**
- Modify: `docs/agents/architecture.md` (market 모듈 절 추가, 레거시 `domain/model` 목록에서 market 서브패키지 제거)
- Modify: `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` (market을 "완료"로 표시)
- Modify: `README.md` (아키텍처 다이어그램에 market 관련 클래스/패키지명이 있으면 갱신 — 없으면 스킵)

**Interfaces:** 없음(문서 전용, 코드 변경 없음)

- [ ] **Step 1: architecture.md에 market 모듈 절 추가**

기존 `finance`/`notify`/`broker`/`trading` 절과 동일한 형식으로, `com.kista.market/` 트리 구조(domain/model, application/{service,usecase,port/output}, adapter/{in/{web,schedule},out/{feargreed,alpaca,persistence/{calendar,feargreed}}})와 NamedInterface 구성("domain"+"port")을 기술한다. 레거시 `domain/`, `application/`, `adapter/` 절 본문에서 `domain/model/market`, `application/service/market`, `adapter/out/feargreed`, `adapter/out/persistence/calendar`, `adapter/out/persistence/feargreed` 언급을 제거한다. `Spring Modulith 점진 도입` 절의 "finance✅ → notify✅ → broker✅ → trading✅" 뒤에 "→ market✅(5번째)"를 추가.

- [ ] **Step 2: README.md drift 확인**

```bash
grep -n "FearGreed\|MarketHoliday\|market" README.md
```

매치되는 곳이 옛 패키지 경로(`com.kista.adapter.out.feargreed` 등)를 언급하면 `com.kista.market.adapter.out.feargreed`로 갱신. 매치 없으면 이 스텝은 스킵.

- [ ] **Step 3: 스펙 문서에 완료 표시**

`docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`의 "착수 순서" 1단계 항목 중 market 옆에 "✅ 완료(2026-08-31, commit 범위는 이 계획의 5개 태스크)" 각주 추가. 같은 스펙의 "결합도 실측" 표에서 market 행의 "순환 여부: 없음" 판정도 정정 — Task 2 리뷰에서 `market→notify→trading→market` 3단 전이 순환이 실측 발견됐고(Task 3에서 해소), 스펙의 pairwise 검증 방식이 전이 순환을 놓쳤다는 점을 각주로 남긴다(다음 모듈 착수 시 같은 맹점 반복 방지).

- [ ] **Step 4: 전체 테스트 스위트 최종 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 실패 0. (참고: 2026-08-30 시점 전체 스위트 1820개 규모 — 이번 이전으로 순수 이동이라 총 테스트 개수는 변하지 않아야 한다.)

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
docs(modulith): market 모듈 이전 반영 — architecture.md/스펙 갱신

EOF
)"
```

---

## Self-Review 메모 (계획 작성자 기준)

- **스펙 커버리지**: 스펙의 "착수 순서 (실측 기반, v2)" 1단계 중 market 항목 전체를 이 계획이 커버함 — 이동 대상 30개 main 파일 + 12개 test 파일 + 외부 소비자 4곳 import 정합화 + 모듈 선언 + 문서 전부 태스크에 반영됨.
- **플레이스홀더 스캔**: 없음 — 모든 Step에 실행 가능한 정확한 명령어/경로 명시.
- **타입 일관성**: Task 1이 만든 6개 port/2개 usecase/2개 domain 타입 FQN이 Task 2·5에서 동일하게 재사용됨. Task 3이 신설한 `FearGreedFetchFailedEvent`는 Task 5의 NamedInterface 선언 대상(root package-info)에도 반영됨.
- **스코프 경계**: `MarketIndexPrice*` 계열(stats 소유)과 레거시 `AlpacaIndexPriceAdapter`는 Global Constraints에 명시적으로 배제 — Task 2의 AlpacaConfig/Properties 복제 처리가 이 경계를 지키는 핵심 지점이므로 리뷰 시 특히 확인.
