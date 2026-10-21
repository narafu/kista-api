# 포트 위치 전환 — 레거시(com.kista.domain) 청크 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 top-level `com.kista.domain.port.{in,out}`(59개 포트 인터페이스)를 `com.kista.application.usecase`/`com.kista.application.port.output`로 이전하고, 이를 강제하는 ArchUnit 규칙과 관련 문서를 갱신한다. 5개 청크(레거시→finance→notify→broker→trading) 중 첫 번째.

**Architecture:** `domain/port/in`·`domain/port/out` 두 패키지는 이번 이동으로 내용물이 완전히 비므로(전량 이동), 옛 패키지를 참조하던 전체 코드베이스의 import를 안전하게 일괄 sed 치환할 수 있다(broker 이전 Task 1과 동일 기법). **Task 1**(port/in 30개 이동 + 전역 import 정합화), **Task 2**(port/out 29개 이동 + 전역 import 정합화), **Task 3**(ArchUnit 규칙 2곳 재작성 + 문서 갱신 + 전체 테스트 스위트 최종 검증). 로직 변경 없음 — 패키지 위치와 import 경로만 바뀐다.

**Tech Stack:** Java 21, Spring Boot 4, Gradle, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-30-port-location-migration-design.md`

## Global Constraints

- 작업 위치: **새 worktree 생성 필요** — `superpowers:using-git-worktrees` 스킬로 브랜치 `worktree-port-location-legacy-migration` 신규 생성 후 그 위에서 진행
- 이 청크는 `com.kista.domain`/`com.kista.application`이 이미 `Type.OPEN` 모듈이라 NamedInterface 재구성이 불필요하다(스펙 "레거시" 절) — Task 3의 ArchUnit 규칙 재작성만 필요
- **ArchUnit 규칙 변경(`HexagonalArchitectureTest` 2곳)은 이 레거시 청크에서 1회만 수행한다** — 규칙이 `com.kista..application.port.output..` 같은 프로젝트 전역 와일드카드 패턴이라, 이후 finance/notify/broker/trading 청크는 포트를 옮기기만 하면 이미 일반화된 패턴이 자동으로 커버한다(재수정 불필요, 이 사실을 finance 청크 계획 착수 시 재확인만 할 것)
- 이 작업은 로직 변경이 전혀 없는 순수 위치 이동이므로, Task 1·2는 `compileJava`/`compileTestJava` 통과를 검증 기준으로 삼고 전체 테스트 스위트 실행은 Task 3 최종 1회로 미룬다(전역 CLAUDE.md "빌드/테스트 전체 스위트는 최종 1회만" 원칙 — 이 변경은 거의 전체 아그리게이트에 걸쳐 있어 특정 `--tests` 서브셋으로 좁혀도 절감 효과가 작음)
- 커밋 메시지: 한글, Conventional Commit 접두사(`refactor:`/`docs:`), author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고**
- macOS(`darwin`) 기준 `sed -i ''` 문법 사용 — GNU sed(`sed -i`)와 다름
- 파일 인코딩: 서브에이전트가 import 수정 시 BOM 삽입 주의(constraints.md "파일 인코딩 주의")

---

### Task 1: `domain/port/in`(30개) → `application/usecase` 이전 + 전역 import 정합화

**Files:**
- Move → `src/main/java/com/kista/application/usecase/`: `AccountStatisticsUseCase.java, AccountUseCase.java, AdminQueryUseCase.java, AdminReorderUseCase.java, AdminSettingsUseCase.java, AdminStrategyUseCase.java, AdminTradeCorrectionUseCase.java, AdminUserUseCase.java, BacktestUseCase.java, BlacklistUseCase.java, FetchFearGreedUseCase.java, FetchHousingBenchmarkUseCase.java, FetchHousingPriceIndexUseCase.java, GetFearGreedUseCase.java, GetUserSettingsQuery.java, MarketUseCase.java, PortfolioUseCase.java, PrivacyTradeValidationUseCase.java, PrivacyUseCase.java, RuntimeSettingsUseCase.java, StrategyUseCase.java, SyncMarketIndexPricesUseCase.java, TokenUseCase.java, TossStatisticsUseCase.java, UpdateBalanceCheckUseCase.java, UpdateNotificationPrefUseCase.java, UpdateStrategySuggestionsUseCase.java, UserProfileUseCase.java, UserStatsUseCase.java, UserUseCase.java`(기존 `domain/port/in/*` 30개, `GetUserSettingsQuery`만 `*UseCase` 접미사 예외)
- Modify (import 경로 일괄 sed, 아래 Step 2): `com.kista.domain.port.in.*`을 참조하는 전체 파일(정확한 목록은 Step 1 직전 grep이 실시간 산출 — 착수 시점 기준 113개, controller/service/adapter 전 아그리게이트에 걸침)

**Interfaces:**
- Produces: `com.kista.application.usecase.*`(30개) — Task 2 착수 시점엔 무관, Task 3의 ArchUnit `adapter.in` 규칙 재작성이 이 패키지를 예외 허용 대상으로 삼는다

- [ ] **Step 0: 착수 직전 재확인 — 대상 30개 파일 존재 확인 + 참조 파일 목록 재산출**

```bash
find src/main/java/com/kista/domain/port/in -name "*.java" | wc -l
```
Expected: `30`. 다르면(스펙 작성 이후 신규 UseCase 추가/삭제) 아래 git mv 목록을 실제 파일 목록에 맞춰 조정.

```bash
grep -rl "import com\.kista\.domain\.port\.in\." src/main/java src/test/java | grep -v "^src/main/java/com/kista/domain/port/in/" > /tmp/portin-consumers.txt
wc -l /tmp/portin-consumers.txt
```
Expected: 113 근처(스펙 작성 시점 기준). 착수 직전 새로 참조가 추가/삭제됐을 수 있으니 이 파일 목록을 이후 Step에서 실제 검증 기준으로 삼는다.

- [ ] **Step 1: 30개 파일 이동 + 패키지 선언 변경**

```bash
mkdir -p src/main/java/com/kista/application/usecase
git mv src/main/java/com/kista/domain/port/in/AccountStatisticsUseCase.java src/main/java/com/kista/application/usecase/AccountStatisticsUseCase.java
git mv src/main/java/com/kista/domain/port/in/AccountUseCase.java src/main/java/com/kista/application/usecase/AccountUseCase.java
git mv src/main/java/com/kista/domain/port/in/AdminQueryUseCase.java src/main/java/com/kista/application/usecase/AdminQueryUseCase.java
git mv src/main/java/com/kista/domain/port/in/AdminReorderUseCase.java src/main/java/com/kista/application/usecase/AdminReorderUseCase.java
git mv src/main/java/com/kista/domain/port/in/AdminSettingsUseCase.java src/main/java/com/kista/application/usecase/AdminSettingsUseCase.java
git mv src/main/java/com/kista/domain/port/in/AdminStrategyUseCase.java src/main/java/com/kista/application/usecase/AdminStrategyUseCase.java
git mv src/main/java/com/kista/domain/port/in/AdminTradeCorrectionUseCase.java src/main/java/com/kista/application/usecase/AdminTradeCorrectionUseCase.java
git mv src/main/java/com/kista/domain/port/in/AdminUserUseCase.java src/main/java/com/kista/application/usecase/AdminUserUseCase.java
git mv src/main/java/com/kista/domain/port/in/BacktestUseCase.java src/main/java/com/kista/application/usecase/BacktestUseCase.java
git mv src/main/java/com/kista/domain/port/in/BlacklistUseCase.java src/main/java/com/kista/application/usecase/BlacklistUseCase.java
git mv src/main/java/com/kista/domain/port/in/FetchFearGreedUseCase.java src/main/java/com/kista/application/usecase/FetchFearGreedUseCase.java
git mv src/main/java/com/kista/domain/port/in/FetchHousingBenchmarkUseCase.java src/main/java/com/kista/application/usecase/FetchHousingBenchmarkUseCase.java
git mv src/main/java/com/kista/domain/port/in/FetchHousingPriceIndexUseCase.java src/main/java/com/kista/application/usecase/FetchHousingPriceIndexUseCase.java
git mv src/main/java/com/kista/domain/port/in/GetFearGreedUseCase.java src/main/java/com/kista/application/usecase/GetFearGreedUseCase.java
git mv src/main/java/com/kista/domain/port/in/GetUserSettingsQuery.java src/main/java/com/kista/application/usecase/GetUserSettingsQuery.java
git mv src/main/java/com/kista/domain/port/in/MarketUseCase.java src/main/java/com/kista/application/usecase/MarketUseCase.java
git mv src/main/java/com/kista/domain/port/in/PortfolioUseCase.java src/main/java/com/kista/application/usecase/PortfolioUseCase.java
git mv src/main/java/com/kista/domain/port/in/PrivacyTradeValidationUseCase.java src/main/java/com/kista/application/usecase/PrivacyTradeValidationUseCase.java
git mv src/main/java/com/kista/domain/port/in/PrivacyUseCase.java src/main/java/com/kista/application/usecase/PrivacyUseCase.java
git mv src/main/java/com/kista/domain/port/in/RuntimeSettingsUseCase.java src/main/java/com/kista/application/usecase/RuntimeSettingsUseCase.java
git mv src/main/java/com/kista/domain/port/in/StrategyUseCase.java src/main/java/com/kista/application/usecase/StrategyUseCase.java
git mv src/main/java/com/kista/domain/port/in/SyncMarketIndexPricesUseCase.java src/main/java/com/kista/application/usecase/SyncMarketIndexPricesUseCase.java
git mv src/main/java/com/kista/domain/port/in/TokenUseCase.java src/main/java/com/kista/application/usecase/TokenUseCase.java
git mv src/main/java/com/kista/domain/port/in/TossStatisticsUseCase.java src/main/java/com/kista/application/usecase/TossStatisticsUseCase.java
git mv src/main/java/com/kista/domain/port/in/UpdateBalanceCheckUseCase.java src/main/java/com/kista/application/usecase/UpdateBalanceCheckUseCase.java
git mv src/main/java/com/kista/domain/port/in/UpdateNotificationPrefUseCase.java src/main/java/com/kista/application/usecase/UpdateNotificationPrefUseCase.java
git mv src/main/java/com/kista/domain/port/in/UpdateStrategySuggestionsUseCase.java src/main/java/com/kista/application/usecase/UpdateStrategySuggestionsUseCase.java
git mv src/main/java/com/kista/domain/port/in/UserProfileUseCase.java src/main/java/com/kista/application/usecase/UserProfileUseCase.java
git mv src/main/java/com/kista/domain/port/in/UserStatsUseCase.java src/main/java/com/kista/application/usecase/UserStatsUseCase.java
git mv src/main/java/com/kista/domain/port/in/UserUseCase.java src/main/java/com/kista/application/usecase/UserUseCase.java
rmdir src/main/java/com/kista/domain/port/in
sed -i '' 's/^package com\.kista\.domain\.port\.in;/package com.kista.application.usecase;/' src/main/java/com/kista/application/usecase/*.java
```

- [ ] **Step 2: 전역 import 경로 일괄 치환**

`domain/port/in`이 이번 이동으로 완전히 비므로(잔존 파일 없음), 와일드카드 import와 개별 클래스 import 모두 하나의 캡처그룹 패턴으로 안전하게 일괄 치환 가능(broker 이전 Task 1과 동일 기법). 한 줄에 다른 import와 세미콜론으로 붙어있는 경우(`import X; import com.kista.domain.port.in.Y;`)도 `^` 앵커 없이 전역 매칭이라 안전하게 처리된다.

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/import com\.kista\.domain\.port\.in\.\*;/import com.kista.application.usecase.*;/g' \
  -e 's/import com\.kista\.domain\.port\.in\.\([A-Za-z]*\);/import com.kista.application.usecase.\1;/g'
```

- [ ] **Step 3: 잔여 old-path import 재스캔**

```bash
grep -rn "import com\.kista\.domain\.port\.in\." src/main/java src/test/java
```
Expected: 결과 없음. 남아있으면 해당 파일을 열어 수동 교정(클래스명에 숫자·언더스코어가 섞여 `[A-Za-z]*` 패턴을 벗어난 경우 등).

- [ ] **Step 4: 이동 확인**

```bash
find src/main/java/com/kista/application/usecase -name "*.java" | wc -l
```
Expected: `30`.

- [ ] **Step 5: compileJava, compileTestJava 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 둘 다. `cannot find symbol` 에러가 나오면 해당 파일의 import를 확인 — Step 2 sed가 못 잡은 패턴(정적 import, 공백 변형 등)일 수 있다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git status --short   # 의도한 파일만 포함됐는지 확인 (특히 rename 인식 여부)
git commit -m "$(cat <<'EOF'
refactor(port): 인바운드 포트(UseCase/Query) 30개를 domain/port/in에서 application/usecase로 이전

domain/port/in의 UseCase 인터페이스 30개(GetUserSettingsQuery만 Query 접미사 예외)를
application/usecase로 물리 이동. domain/port/in을 참조하던 전체 코드베이스(컨트롤러/
서비스/어댑터 전 아그리게이트)의 import 경로를 일괄 갱신 — 인터페이스 이름·시그니처·
로직 변경 없음, 패키지 위치만 이동.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01D1GHxCK7iaGfxVVb6zQvY5
EOF
)"
```

---

### Task 2: `domain/port/out`(29개) → `application/port/output` 이전 + 전역 import 정합화

**Files:**
- Move → `src/main/java/com/kista/application/port/output/`: `AccountPort.java, AdminUserViewPort.java, AppErrorLogPort.java, AuditLogPort.java, BlacklistPort.java, CnnFearGreedPort.java, CryptoFearGreedPort.java, FearGreedSnapshotPort.java, HeartbeatPort.java, HistoricalCandlePort.java, HousingBenchmarkFeedPort.java, HousingBenchmarkPricePort.java, HousingPriceIndexPort.java, IndexPriceFeedPort.java, IndexPricePort.java, KakaoOAuthPort.java, MarketCalendarPort.java, MarketCalendarRefreshPort.java, MarketHolidayStorePort.java, PrivacyTradePort.java, RealtimeNotificationPort.java, RefreshTokenPort.java, RuntimeSettingsPort.java, StrategyInfiniteDetailPort.java, StrategyPort.java, StrategyVersionPort.java, StrategyVrDetailPort.java, UserPort.java, UserSettingsPort.java`(기존 `domain/port/out/*` 29개)
- Modify (import 경로 일괄 sed, 아래 Step 2): `com.kista.domain.port.out.*`을 참조하는 전체 파일(착수 시점 기준 178개, 와일드카드 import 23건 포함 — `UserCascadeDeleter, StatsService, AdminQueryService, UserService, CyclePositionPersistor, StrategyService, VrCycleRolloverService, TradingReporter, ManualTradingService, CycleRotationService, TradingService` + 대응 테스트 다수, 위 스펙 "크로스모듈 import 사전 스캔" 절 참고)

**Interfaces:**
- Produces: `com.kista.application.port.output.*`(29개) — Task 3의 ArchUnit `*Port` 접미사 규칙이 이 패키지를 새 검증 대상으로 삼는다

- [ ] **Step 0: 착수 직전 재확인 — 대상 29개 파일 존재 확인 + 참조 파일 목록 재산출**

```bash
find src/main/java/com/kista/domain/port/out -name "*.java" | wc -l
```
Expected: `29`. 다르면 아래 git mv 목록을 실제 파일 목록에 맞춰 조정.

```bash
grep -rl "import com\.kista\.domain\.port\.out\." src/main/java src/test/java | grep -v "^src/main/java/com/kista/domain/port/out/" > /tmp/portout-consumers.txt
wc -l /tmp/portout-consumers.txt
grep -rln "import com\.kista\.domain\.port\.out\.\*;" src/main/java src/test/java
```
Expected: 178 근처, 와일드카드 23개 파일 근처.

- [ ] **Step 1: 29개 파일 이동 + 패키지 선언 변경**

```bash
mkdir -p src/main/java/com/kista/application/port/output
git mv src/main/java/com/kista/domain/port/out/AccountPort.java src/main/java/com/kista/application/port/output/AccountPort.java
git mv src/main/java/com/kista/domain/port/out/AdminUserViewPort.java src/main/java/com/kista/application/port/output/AdminUserViewPort.java
git mv src/main/java/com/kista/domain/port/out/AppErrorLogPort.java src/main/java/com/kista/application/port/output/AppErrorLogPort.java
git mv src/main/java/com/kista/domain/port/out/AuditLogPort.java src/main/java/com/kista/application/port/output/AuditLogPort.java
git mv src/main/java/com/kista/domain/port/out/BlacklistPort.java src/main/java/com/kista/application/port/output/BlacklistPort.java
git mv src/main/java/com/kista/domain/port/out/CnnFearGreedPort.java src/main/java/com/kista/application/port/output/CnnFearGreedPort.java
git mv src/main/java/com/kista/domain/port/out/CryptoFearGreedPort.java src/main/java/com/kista/application/port/output/CryptoFearGreedPort.java
git mv src/main/java/com/kista/domain/port/out/FearGreedSnapshotPort.java src/main/java/com/kista/application/port/output/FearGreedSnapshotPort.java
git mv src/main/java/com/kista/domain/port/out/HeartbeatPort.java src/main/java/com/kista/application/port/output/HeartbeatPort.java
git mv src/main/java/com/kista/domain/port/out/HistoricalCandlePort.java src/main/java/com/kista/application/port/output/HistoricalCandlePort.java
git mv src/main/java/com/kista/domain/port/out/HousingBenchmarkFeedPort.java src/main/java/com/kista/application/port/output/HousingBenchmarkFeedPort.java
git mv src/main/java/com/kista/domain/port/out/HousingBenchmarkPricePort.java src/main/java/com/kista/application/port/output/HousingBenchmarkPricePort.java
git mv src/main/java/com/kista/domain/port/out/HousingPriceIndexPort.java src/main/java/com/kista/application/port/output/HousingPriceIndexPort.java
git mv src/main/java/com/kista/domain/port/out/IndexPriceFeedPort.java src/main/java/com/kista/application/port/output/IndexPriceFeedPort.java
git mv src/main/java/com/kista/domain/port/out/IndexPricePort.java src/main/java/com/kista/application/port/output/IndexPricePort.java
git mv src/main/java/com/kista/domain/port/out/KakaoOAuthPort.java src/main/java/com/kista/application/port/output/KakaoOAuthPort.java
git mv src/main/java/com/kista/domain/port/out/MarketCalendarPort.java src/main/java/com/kista/application/port/output/MarketCalendarPort.java
git mv src/main/java/com/kista/domain/port/out/MarketCalendarRefreshPort.java src/main/java/com/kista/application/port/output/MarketCalendarRefreshPort.java
git mv src/main/java/com/kista/domain/port/out/MarketHolidayStorePort.java src/main/java/com/kista/application/port/output/MarketHolidayStorePort.java
git mv src/main/java/com/kista/domain/port/out/PrivacyTradePort.java src/main/java/com/kista/application/port/output/PrivacyTradePort.java
git mv src/main/java/com/kista/domain/port/out/RealtimeNotificationPort.java src/main/java/com/kista/application/port/output/RealtimeNotificationPort.java
git mv src/main/java/com/kista/domain/port/out/RefreshTokenPort.java src/main/java/com/kista/application/port/output/RefreshTokenPort.java
git mv src/main/java/com/kista/domain/port/out/RuntimeSettingsPort.java src/main/java/com/kista/application/port/output/RuntimeSettingsPort.java
git mv src/main/java/com/kista/domain/port/out/StrategyInfiniteDetailPort.java src/main/java/com/kista/application/port/output/StrategyInfiniteDetailPort.java
git mv src/main/java/com/kista/domain/port/out/StrategyPort.java src/main/java/com/kista/application/port/output/StrategyPort.java
git mv src/main/java/com/kista/domain/port/out/StrategyVersionPort.java src/main/java/com/kista/application/port/output/StrategyVersionPort.java
git mv src/main/java/com/kista/domain/port/out/StrategyVrDetailPort.java src/main/java/com/kista/application/port/output/StrategyVrDetailPort.java
git mv src/main/java/com/kista/domain/port/out/UserPort.java src/main/java/com/kista/application/port/output/UserPort.java
git mv src/main/java/com/kista/domain/port/out/UserSettingsPort.java src/main/java/com/kista/application/port/output/UserSettingsPort.java
rmdir src/main/java/com/kista/domain/port/out
rmdir src/main/java/com/kista/domain/port
sed -i '' 's/^package com\.kista\.domain\.port\.out;/package com.kista.application.port.output;/' src/main/java/com/kista/application/port/output/*.java
```

- [ ] **Step 2: 전역 import 경로 일괄 치환**

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/import com\.kista\.domain\.port\.out\.\*;/import com.kista.application.port.output.*;/g' \
  -e 's/import com\.kista\.domain\.port\.out\.\([A-Za-z]*\);/import com.kista.application.port.output.\1;/g'
```

- [ ] **Step 3: 잔여 old-path import 재스캔**

```bash
grep -rn "import com\.kista\.domain\.port\.out\." src/main/java src/test/java
grep -rn "com\.kista\.domain\.port" src/main/java src/test/java   # domain/port 디렉토리 자체 참조 흔적 최종 확인
```
Expected: 둘 다 결과 없음.

- [ ] **Step 4: 이동 확인**

```bash
find src/main/java/com/kista/application/port/output -name "*.java" | wc -l
find src/main/java/com/kista/domain/port -maxdepth 0 2>/dev/null   # 디렉토리 자체가 사라졌는지 확인
```
Expected: `29`, 두번째 명령은 아무 출력 없음(디렉토리 없어짐).

- [ ] **Step 5: compileJava, compileTestJava 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 둘 다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git status --short
git commit -m "$(cat <<'EOF'
refactor(port): 아웃바운드 포트(*Port) 29개를 domain/port/out에서 application/port/output으로 이전

domain/port/out의 *Port 인터페이스 29개를 application/port/output으로 물리 이동.
domain/port/out을 참조하던 전체 코드베이스(와일드카드 import 23건 포함)의 import
경로를 일괄 갱신 — 인터페이스 이름·시그니처·로직 변경 없음, 패키지 위치만 이동.
domain/port 디렉토리 자체가 이번 이동으로 완전히 비어 함께 제거됨.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01D1GHxCK7iaGfxVVb6zQvY5
EOF
)"
```

---

### Task 3: ArchUnit 규칙 재작성 + 문서 갱신 + 전체 테스트 스위트 최종 검증

**Files:**
- Modify: `src/test/java/com/kista/architecture/HexagonalArchitectureTest.java`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/agents/architecture.md`
- Modify: `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`(보류 항목 진행 상태 갱신)

**Interfaces:** 없음 (테스트 규칙 + 문서 전용)

- [ ] **Step 1: `HexagonalArchitectureTest` 규칙 2곳 재작성**

`src/test/java/com/kista/architecture/HexagonalArchitectureTest.java:47-55`(인바운드 어댑터 규칙)을 다음으로 교체:

```java
    @Test
    @DisplayName("인바운드 어댑터는 application.service(구현체)에 직접 의존하지 않는다")
    void inbound_adapters_must_not_depend_on_application_layer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista..adapter.in..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista..application.service..");
        rule.check(classes);
    }
```

`src/test/java/com/kista/architecture/HexagonalArchitectureTest.java:77-87`(`*Port` 접미사 규칙)을 다음으로 교체:

```java
    @Test
    @DisplayName("application/port/output 인터페이스는 *Port 접미사를 가져야 한다")
    void outbound_port_interfaces_must_have_Port_suffix() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista..application.port.output..")
                .and().areInterfaces()
                // package-info.class는 ACC_INTERFACE 플래그로 컴파일되어 areInterfaces()에 오탐 매칭됨 — 제외
                .and().doNotHaveSimpleName("package-info")
                .should().haveSimpleNameEndingWith("Port");
        rule.check(classes);
    }
```

- [ ] **Step 2: ArchUnit 테스트 2개 실행**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: 전부 PASS. `adapter.in` 규칙이 실패하면(컨트롤러가 `application.usecase`를 참조하는데도 걸림) 정규식이 `application.service..`로 정확히 좁혀졌는지 재확인. `*Port` 접미사 규칙이 실패하면 Task 2에서 이동한 29개 파일 이름이 전부 `*Port`로 끝나는지 확인(스펙 단계에서 이미 확인 완료했으므로 나오면 안 됨).

- [ ] **Step 3: constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 섹션 개정**

`docs/agents/constraints.md`에서 해당 섹션을 찾아 교체:

```diff
- ### 도메인 포트 인터페이스와 타입 위치 규칙
- - `domain/port/in` 또는 `domain/port/out` 인터페이스의 파라미터·반환 타입으로 쓰이는 record/class는 반드시 `domain/model/` 하위에 위치 — `adapter/in/web/dto/`에 두면 `domain → adapter` ArchUnit 규칙 위반
- - `application/service`도 마찬가지로 `adapter` 패키지 import 금지 (`application → adapter` 규칙)
- - 컨트롤러 DTO와 겹치는 타입이 있으면 `domain/model/<도메인>` 패키지로 이동 후 DTO에서 re-import
+ ### 포트 인터페이스 위치 규칙
+ - 인바운드 포트(UseCase/Query 인터페이스)는 `application/usecase/`, 아웃바운드 포트(`*Port`)는 `application/port/output/`에 위치 — `domain/port/{in,out}`은 더 이상 사용하지 않는다
+ - 포트의 파라미터·반환 타입으로 쓰이는 record/class는 여전히 `domain/model/` 하위에 위치 — 포트 위치가 옮겨가도 타입 소유는 domain 유지. `adapter/in/web/dto/`에 두면 `domain → adapter` ArchUnit 규칙 위반
+ - `adapter/in`(컨트롤러 등)은 `application.usecase`/`application.port.output`(인터페이스)에는 의존 가능하지만 `application.service`(구현체)에는 의존 금지 — ArchUnit이 이 경계만 강제
+ - `application.service`도 마찬가지로 `adapter` 패키지 import 금지 (`application → adapter` 규칙, 변경 없음)
+ - 컨트롤러 DTO와 겹치는 타입이 있으면 `domain/model/<도메인>` 패키지로 이동 후 DTO에서 re-import (변경 없음)
```

바로 아래 "domain/port/out/ 네이밍 규칙" 섹션도 교체:

```diff
- ### domain/port/out/ 네이밍 규칙
- - 아웃바운드 포트 인터페이스: `*Port` 접미사. `*Repository` 접미사 사용 금지 — adapter 레이어 `*JpaRepository`와 혼동 유발
+ ### application/port/output/ 네이밍 규칙
+ - 아웃바운드 포트 인터페이스: `*Port` 접미사. `*Repository` 접미사 사용 금지 — adapter 레이어 `*JpaRepository`와 혼동 유발
```

- [ ] **Step 4: constraints.md 그 외 경로 언급 확인**

```bash
grep -n "domain/port/in\|domain/port/out\|domain\.port\.\(in\|out\)" docs/agents/constraints.md
```
결과가 있는 각 라인을 `application/usecase`/`application/port/output` 또는 `application.usecase`/`application.port.output`로 갱신. 특히 "Spring Modulith 이전 중 신규 파일 배치" 섹션에서 `com.kista.finance.domain.port.out` 등 각 모듈 포트 경로를 언급하는 부분은 이번 레거시 청크에선 아직 finance/notify/broker/trading 이전 전이므로 손대지 않는다(각 청크 자체 계획에서 갱신).

- [ ] **Step 5: architecture.md 레거시 패키지 맵 갱신**

`docs/agents/architecture.md` 최상단 hex 레이어 개요에서 다음 라인을 찾아 갱신:

```diff
- port/in/       ← UseCase 인터페이스 (인바운드 포트) (TradingExecutionUseCase/VrReconfigureUseCase는 com.kista.trading.domain.port.in으로 이전됨)
- port/out/      ← 아웃바운드 포트 인터페이스 (*Port) (broker Capability 인터페이스는 com.kista.broker.domain.port.out으로, ...)
+ (domain/port/in, domain/port/out 폐지됨 — 레거시 포트는 application/usecase, application/port/output로 이전됨, 아래 application/ 절 참고)
```

`application/` 섹션 최상단에 신규 라인 추가:

```
usecase/       ← UseCase/Query 인터페이스 (인바운드 포트, 레거시 30개 — 옛 domain/port/in에서 이전됨. 각 모듈(finance/notify/broker/trading)의 usecase는 해당 모듈 절 참고)
port/output/   ← *Port 접미사 아웃바운드 포트 인터페이스 (레거시 29개 — 옛 domain/port/out에서 이전됨. 각 모듈의 port는 해당 모듈 절 참고)
```

- [ ] **Step 6: 상위 스펙 문서 진행 상태 갱신**

`docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`에서 "보류(나중에 별도 작업으로, 순서 무관하게 각각 독립)" 절의 1번 항목("포트 위치를 domain → application(`usecase`/`port/output`)으로 전환...")에 진행 상태를 추가:

```diff
- 1. 포트 위치를 domain → application(`usecase`/`port/output`)으로 전환. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요
+ 1. 포트 위치를 domain → application(`usecase`/`port/output`)으로 전환. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요 — **착수함**(레거시 청크 완료, `docs/superpowers/specs/2026-08-30-port-location-migration-design.md` 참고). 레거시→finance→notify→broker→trading 5개 청크 중 레거시 완료, 나머지 4개 진행 예정
```

- [ ] **Step 7: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 테스트 특정 후 수정.

- [ ] **Step 8: 커밋**

```bash
git add src/test/java/com/kista/architecture/HexagonalArchitectureTest.java \
        docs/agents/constraints.md docs/agents/architecture.md \
        docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
git status --short
git commit -m "$(cat <<'EOF'
refactor(architecture): ArchUnit 포트 위치 규칙을 application/{usecase,port.output} 기준으로 재작성 + 문서 갱신

인바운드 어댑터 비의존 규칙을 application 전체가 아닌 application.service(구현체)로
좁혀 application.usecase/application.port.output(인터페이스) 의존을 허용. *Port
접미사 규칙 대상 패키지를 domain.port.out에서 application.port.output으로 변경.
이 두 규칙은 프로젝트 전역 와일드카드라 이후 finance/notify/broker/trading 청크는
포트만 옮기면 자동 커버된다.

constraints.md "도메인 포트 인터페이스와 타입 위치 규칙"/"domain/port/out/ 네이밍
규칙" 섹션과 architecture.md 레거시 패키지 맵을 새 위치 기준으로 갱신.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01D1GHxCK7iaGfxVVb6zQvY5
EOF
)"
```

---

## 리팩토링 관찰 체크포인트 (모든 태스크 공통)

각 태스크 실행 중 스펙에 없는 개선 지점(우아하지 않은 코드, 중복, 더 단순한 구현 가능성 등)을 발견하면:
1. **임의로 고치지 않는다** — 이 계획의 스코프 밖 변경은 사용자 승인 필요
2. 발견 즉시 사용자에게 짧게 보고(무엇을, 어디서, 왜 개선 여지가 있는지)

## 다음 청크

이 레거시 청크가 main에 병합되면, `docs/superpowers/specs/2026-08-30-port-location-migration-design.md`의 finance 청크(16개 포트 파일 — NamedInterface 재구성 포함)에 대한 별도 계획 문서를 `writing-plans`로 새로 작성한다. 레거시 청크에서 확립된 sed 기법·태스크 분할 패턴을 그대로 재사용하되, finance는 `"domain"` NamedInterface를 `"domain"`(model만)+`"usecase"`+`"port"` 3개로 재구성하는 태스크가 추가된다(스펙 "NamedInterface 재구성" 표 참고).
