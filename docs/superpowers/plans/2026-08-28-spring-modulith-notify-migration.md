# Spring Modulith notify 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** notify 애그리게이트(Telegram/FCM 알림 발송)를 `com.kista.notify` Spring Modulith 모듈로 이전한다.

**Architecture:** finance 모듈 이전(브랜치 `worktree-modulith-finance-migration`)과 동일한 패턴을 따른다 — (1) 파일을 `git mv`로 물리 이동하고 전체 코드베이스 참조를 컴파일이 통과할 때까지 정합화, (2) `@ApplicationModule` 선언 + `NamedInterface`로 공개 계약 확정, (3) 관련 문서 갱신. 3개 태스크로 진행하며 태스크 경계마다 커밋한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit5/Mockito, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-28-spring-modulith-notify-migration-design.md` (원칙 SSOT는 `2026-08-27-spring-modulith-migration-design.md`)

## Global Constraints

- 작업 위치: `.claude/worktrees/modulith-finance-migration` (브랜치 `worktree-modulith-finance-migration`) — 이 브랜치 위에서 이어감, 새 worktree 생성하지 않음
- 포트는 `domain/port/out` 위치 그대로 유지 — `application/port`로 전환하지 않음 (constraints.md "도메인 포트 인터페이스와 타입 위치 규칙")
- 커밋 메시지: 한글, Conventional Commit 접두사(`refactor:`/`feat:`/`docs:`/`fix:`), author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고** (사용자가 이 작업이 모듈 분리+리팩토링 겸용이라고 명시함) — 이미 스펙에 반영된 항목(FcmDeviceToken 삭제, gateway 명명)은 예외, 그 외 신규 발견 건에 적용
- 전체 테스트 스위트(`./gradlew test`)는 Task 3 완료 후 최종 1회만 — Task 1/2 진행 중엔 `--tests`로 좁혀서 검증
- 파일 인코딩: 서브에이전트가 import 수정 시 BOM 삽입 주의(constraints.md "파일 인코딩 주의")

---

### Task 1: notify 애그리게이트 파일 이동 + 전체 참조 컴파일 정합화

**Files:**
- Delete: `src/main/java/com/kista/domain/model/user/FcmDeviceToken.java` (dead code, 사용처 0건 확인됨)
- Move → `src/main/java/com/kista/notify/domain/port/out/`: `NotifyPort.java`, `UserNotificationPort.java`, `FcmDeviceTokenPort.java`, `TelegramBotInfoPort.java`
- Move → `src/main/java/com/kista/notify/adapter/out/gateway/`: `TelegramConfig.java`, `TelegramProperties.java`, `TelegramHttpClient.java`, `TelegramAdapter.java`, `TelegramBotInfoAdapter.java`, `TelegramUserNotificationAdapter.java`, `FcmConfig.java`, `FcmAdapter.java`, `CompositeUserNotificationAdapter.java`, `CycleEndedNotifier.java`, `CycleLifecycleNotifier.java`, `TradingReportNotifier.java`, `UserDeletedNotifier.java`, `OrderCancelFailureNotifier.java` (기존 `adapter/out/notify/*` 14개)
- Move → `src/main/java/com/kista/notify/adapter/out/persistence/`: `FcmDeviceTokenPersistenceAdapter.java`, `FcmDeviceTokenEntity.java`, `FcmDeviceTokenJpaRepository.java` (기존 `adapter/out/persistence/fcm/*`)
- Move → `src/main/java/com/kista/notify/adapter/in/telegram/`: `TelegramUpdate.java`, `TelegramBotService.java`, `TelegramWebhookController.java`, `TelegramApiClient.java`
- Move → `src/test/java/com/kista/notify/adapter/out/gateway/`: `CompositeUserNotificationAdapterTest.java`, `CycleEndedNotifierTest.java`, `CycleLifecycleNotifierTest.java`, `FcmAdapterTest.java`, `OrderCancelFailureNotifierTest.java`, `TelegramAdapterTest.java`, `TelegramUserNotificationAdapterTest.java`, `TradingReportNotifierTest.java`, `UserDeletedNotifierTest.java` (기존 `adapter/out/notify/*Test.java` 9개)
- Move → `src/test/java/com/kista/notify/adapter/out/persistence/`: `FcmDeviceTokenPersistenceAdapterTest.java`
- Move → `src/test/java/com/kista/notify/adapter/in/telegram/`: `TelegramApiClientTest.java`, `TelegramBotServiceTest.java`, `TelegramWebhookControllerTest.java`
- Modify (import 경로, sed 일괄 처리 — 아래 Step 8 참고): `adapter/in/schedule/{BatchContextFactory,SchedulerJobRunner,TradingOpenScheduler,RefreshTokenCleanupScheduler}.java`, `adapter/in/aop/ErrorLogAspect.java`, `application/service/privacy/PrivacyService.java`, `application/service/market/FearGreedService.java`, `application/service/stats/{HousingPriceIndexService,HousingBenchmarkService}.java`, `application/service/trading/{TradingPriceFetcher,VrReconfigureService,TradingOrderExecutor,MarketEventNotifier}.java`, `application/service/user/UserProfileService.java`, `finance/application/service/FinanceRegistrationReminderNotifier.java` (+대응 테스트 파일들, `TradingReporterTest.java` 포함)
- Modify (wildcard import라 sed로 안 잡힘, explicit import 수동 추가 — 아래 Step 9 참고): `application/service/trading/{ManualTradingService,TradingService,TradingReporter,VrCycleRolloverService,CycleRotationService}.java`, `application/service/trading/{TradingServiceTest,VrCycleRolloverServiceTest,ManualTradingServiceTest,CycleRotationServiceTest}.java`, `adapter/in/schedule/TradingOpenSchedulerTest.java`

**Interfaces:**
- Produces: `com.kista.notify.domain.port.out.{NotifyPort, UserNotificationPort, FcmDeviceTokenPort, TelegramBotInfoPort}` — Task 2에서 Named Interface로 공개할 4개 인터페이스, 이후 태스크·다른 모듈이 이 FQN으로 참조

- [ ] **Step 1: FcmDeviceToken dead code 삭제**

```bash
cd /Users/phs/workspace/kista/kista-api/.claude/worktrees/modulith-finance-migration
git rm src/main/java/com/kista/domain/model/user/FcmDeviceToken.java
```

- [ ] **Step 2: domain/port/out 4개 이동**

```bash
mkdir -p src/main/java/com/kista/notify/domain/port/out
git mv src/main/java/com/kista/domain/port/out/NotifyPort.java src/main/java/com/kista/notify/domain/port/out/NotifyPort.java
git mv src/main/java/com/kista/domain/port/out/UserNotificationPort.java src/main/java/com/kista/notify/domain/port/out/UserNotificationPort.java
git mv src/main/java/com/kista/domain/port/out/FcmDeviceTokenPort.java src/main/java/com/kista/notify/domain/port/out/FcmDeviceTokenPort.java
git mv src/main/java/com/kista/domain/port/out/TelegramBotInfoPort.java src/main/java/com/kista/notify/domain/port/out/TelegramBotInfoPort.java
sed -i '' 's/^package com\.kista\.domain\.port\.out;/package com.kista.notify.domain.port.out;/' src/main/java/com/kista/notify/domain/port/out/*.java
```

- [ ] **Step 3: adapter/out/notify → adapter/out/gateway 이동 (main 14개 + test 9개)**

```bash
mkdir -p src/main/java/com/kista/notify/adapter/out/gateway
git mv src/main/java/com/kista/adapter/out/notify/*.java src/main/java/com/kista/notify/adapter/out/gateway/
rmdir src/main/java/com/kista/adapter/out/notify
sed -i '' 's/^package com\.kista\.adapter\.out\.notify;/package com.kista.notify.adapter.out.gateway;/' src/main/java/com/kista/notify/adapter/out/gateway/*.java

mkdir -p src/test/java/com/kista/notify/adapter/out/gateway
git mv src/test/java/com/kista/adapter/out/notify/*.java src/test/java/com/kista/notify/adapter/out/gateway/
rmdir src/test/java/com/kista/adapter/out/notify
sed -i '' 's/^package com\.kista\.adapter\.out\.notify;/package com.kista.notify.adapter.out.gateway;/' src/test/java/com/kista/notify/adapter/out/gateway/*.java
```

- [ ] **Step 4: adapter/out/persistence/fcm → adapter/out/persistence 이동 (flat, main 3개 + test 1개)**

```bash
mkdir -p src/main/java/com/kista/notify/adapter/out/persistence
git mv src/main/java/com/kista/adapter/out/persistence/fcm/*.java src/main/java/com/kista/notify/adapter/out/persistence/
rmdir src/main/java/com/kista/adapter/out/persistence/fcm
sed -i '' 's/^package com\.kista\.adapter\.out\.persistence\.fcm;/package com.kista.notify.adapter.out.persistence;/' src/main/java/com/kista/notify/adapter/out/persistence/*.java

mkdir -p src/test/java/com/kista/notify/adapter/out/persistence
git mv src/test/java/com/kista/adapter/out/persistence/fcm/*.java src/test/java/com/kista/notify/adapter/out/persistence/
rmdir src/test/java/com/kista/adapter/out/persistence/fcm
sed -i '' 's/^package com\.kista\.adapter\.out\.persistence\.fcm;/package com.kista.notify.adapter.out.persistence;/' src/test/java/com/kista/notify/adapter/out/persistence/*.java
```

- [ ] **Step 5: adapter/in/telegram 이동 (main 4개 + test 3개)**

```bash
mkdir -p src/main/java/com/kista/notify/adapter/in/telegram
git mv src/main/java/com/kista/adapter/in/telegram/*.java src/main/java/com/kista/notify/adapter/in/telegram/
rmdir src/main/java/com/kista/adapter/in/telegram
sed -i '' 's/^package com\.kista\.adapter\.in\.telegram;/package com.kista.notify.adapter.in.telegram;/' src/main/java/com/kista/notify/adapter/in/telegram/*.java

mkdir -p src/test/java/com/kista/notify/adapter/in/telegram
git mv src/test/java/com/kista/adapter/in/telegram/*.java src/test/java/com/kista/notify/adapter/in/telegram/
rmdir src/test/java/com/kista/adapter/in/telegram
sed -i '' 's/^package com\.kista\.adapter\.in\.telegram;/package com.kista.notify.adapter.in.telegram;/' src/test/java/com/kista/notify/adapter/in/telegram/*.java
```

- [ ] **Step 6: 이동 확인**

```bash
find src/main/java/com/kista/notify src/test/java/com/kista/notify -name "*.java" | wc -l
```
Expected: main 25개(port 4 + gateway 14 + persistence 3 + telegram 4) + test 13개(gateway 9 + persistence 1 + telegram 3) = 38

- [ ] **Step 7: 전역 import 경로 일괄 치환 (explicit import 라인이 있는 파일 전부 커버)**

이 sed는 `import com.kista.domain.port.out.{NotifyPort,UserNotificationPort,FcmDeviceTokenPort,TelegramBotInfoPort};` 형태의 explicit import 라인을 가진 모든 파일(방금 이동한 파일 자신 포함, 외부 old top-level 참조 파일 포함)을 프로젝트 전체에서 한 번에 고친다. wildcard import(`domain.port.out.*`)를 쓰는 파일은 이 sed로 안 잡히므로 Step 9에서 별도 처리한다.

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/^import com\.kista\.domain\.port\.out\.NotifyPort;/import com.kista.notify.domain.port.out.NotifyPort;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.UserNotificationPort;/import com.kista.notify.domain.port.out.UserNotificationPort;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.FcmDeviceTokenPort;/import com.kista.notify.domain.port.out.FcmDeviceTokenPort;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.TelegramBotInfoPort;/import com.kista.notify.domain.port.out.TelegramBotInfoPort;/'
```

- [ ] **Step 8: sed 적용 확인 (old 경로 import가 더 이상 남아있지 않아야 함)**

```bash
grep -rn "import com\.kista\.domain\.port\.out\.\(NotifyPort\|UserNotificationPort\|FcmDeviceTokenPort\|TelegramBotInfoPort\);" src/main/java src/test/java
```
Expected: 결과 없음 (전부 치환됨)

- [ ] **Step 9: wildcard import 파일에 explicit import 라인 수동 추가**

아래 10개 파일은 `import com.kista.domain.port.out.*;` 형태라 Step 7 sed가 적용되지 않는다. 각 파일 import 블록에 새 import 라인을 추가한다(기존 wildcard 라인은 그대로 둠 — 다른 domain.port.out 타입들이 여전히 old 경로에 있으므로 유지 필요).

`application/service/trading/ManualTradingService.java`, `application/service/trading/CycleRotationService.java`, `adapter/in/schedule/TradingOpenSchedulerTest.java` → 추가:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```

`application/service/trading/TradingReporter.java` → 추가:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```

`application/service/trading/TradingService.java`, `application/service/trading/VrCycleRolloverService.java`, `application/service/trading/TradingServiceTest.java`, `application/service/trading/VrCycleRolloverServiceTest.java` → 추가:
```java
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
```

`application/service/trading/ManualTradingServiceTest.java`, `application/service/trading/CycleRotationServiceTest.java` → 추가:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```

- [ ] **Step 10: compileJava 실행, 에러 있으면 반복 수정**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 에러가 나오면 `cannot find symbol` 메시지의 타입명으로 Step 9와 동일한 패턴(누락된 explicit import 추가)을 적용하고 재실행 — Step 7/9 목록에서 빠뜨린 wildcard 파일이 있을 수 있음.

- [ ] **Step 11: compileTestJava 실행, 에러 있으면 반복 수정**

```bash
./gradlew compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 처리 방식은 Step 10과 동일.

- [ ] **Step 12: 이동/영향 범위 테스트 실행**

```bash
./gradlew test --tests 'com.kista.notify.*' \
  --tests 'com.kista.application.service.trading.TradingServiceTest' \
  --tests 'com.kista.application.service.trading.TradingReporterTest' \
  --tests 'com.kista.application.service.trading.ManualTradingServiceTest' \
  --tests 'com.kista.application.service.trading.VrCycleRolloverServiceTest' \
  --tests 'com.kista.application.service.trading.CycleRotationServiceTest' \
  --tests 'com.kista.adapter.in.schedule.TradingOpenSchedulerTest' \
  --tests 'com.kista.application.service.user.UserProfileServiceTest' \
  --tests 'com.kista.finance.application.service.FinanceRegistrationReminderNotifierTest'
```
Expected: 전부 PASS. 실패 시 `docs/agents/commands.md`의 "테스트 실패 진단" 절차(XML 기반) 사용.

- [ ] **Step 13: 커밋**

```bash
git add -A
git status --short   # 의도한 파일만 포함됐는지 확인 (특히 rename 인식 여부)
git commit -m "$(cat <<'EOF'
refactor(notify): notify 애그리게이트를 com.kista.notify 모듈로 이전

domain/port/out, adapter/out/notify(→gateway로 개명), adapter/out/persistence/fcm
(→flat persistence), adapter/in/telegram 아래 흩어진 notify 소유 파일 25개(main)
+13개(test)를 com.kista.notify 하위 self-contained 패키지로 이동.

- FcmDeviceToken(domain/model/user): 사용처 0건 확인된 dead code라 이동 대신 삭제
- adapter/out/notify → adapter/out/gateway: notify.notify 중복 명명 회피
- NotifyPort/UserNotificationPort/FcmDeviceTokenPort/TelegramBotInfoPort를 참조하는
  구 top-level 파일(스케쥴러·trading·privacy·market·stats·user·finance 등) import
  경로 전부 갱신 — 로직 변경 없음
- finance 잔여 항목 정리: FinanceRegistrationReminderNotifier의 UserNotificationPort
  import도 새 notify 경로로 갱신(finance 스펙이 notify 이전 시점에 재정의하기로
  미뤄둔 부분)

com.kista.notify는 아직 @ApplicationModule 미선언 상태(Task 2 예정)라
ModulithArchitectureTest의 verify()는 예상대로 실패.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 2: `@ApplicationModule` 선언 + Named Interface 공개

**Files:**
- Create: `src/main/java/com/kista/notify/package-info.java`
- Create: `src/main/java/com/kista/notify/domain/port/out/package-info.java`

**Interfaces:**
- Consumes: Task 1에서 이동한 `com.kista.notify.domain.port.out.*` 4개 인터페이스
- Produces: `com.kista.notify` 모듈 경계 선언 — 이후 `ModulithArchitectureTest.verifyModularStructure()`가 이 모듈을 포함해 순환 검증

- [ ] **Step 1: 루트 package-info.java 작성**

`src/main/java/com/kista/notify/package-info.java`:
```java
// notify 애그리게이트(Telegram/FCM 알림 발송) 모듈 — domain.port.out만 공개 계약, application/adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.notify;
```

- [ ] **Step 2: domain/port/out Named Interface 작성**

`src/main/java/com/kista/notify/domain/port/out/package-info.java`:
```java
// notify 모듈의 공개 계약 — *Port 접미사 출력 포트 인터페이스. "domain" 이름으로 공개(다른 모듈과 명명 일관성 유지, 현재 notify는 domain/model이 없어 병합 대상 없음).
@org.springframework.modulith.NamedInterface("domain")
package com.kista.notify.domain.port.out;
```

- [ ] **Step 3: ModulithArchitectureTest 실행**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'
```
Expected: PASS (순환 없음, notify가 non-exposed 타입 위반 없이 검증 통과). 실패하면 에러 메시지의 위반 모듈/타입을 확인 — 대부분 old top-level 어댑터가 notify의 non-exposed 타입(예: gateway 패키지의 구현체)을 직접 참조하는 경우인데, Task 1에서 포트만 참조하도록 정리했으므로 나오면 안 됨. 나오면 해당 참조를 찾아 포트 참조로 교정.

- [ ] **Step 4: HexagonalArchitectureTest 회귀 확인**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: PASS — 이미 `..domain.port.out..` 와일드카드 + `package-info` 예외 처리(commit 63f58b66)가 이 브랜치에 있어 추가 수정 불필요. 실패 시에만 조사.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/notify/package-info.java src/main/java/com/kista/notify/domain/port/out/package-info.java
git commit -m "$(cat <<'EOF'
feat(modulith): notify 모듈 선언 — CLOSED + domain Named Interface 공개

notify 애그리게이트를 Spring Modulith ApplicationModule(CLOSED, 기본값)로 선언하고,
domain.port.out을 "domain" 이름으로 NamedInterface 공개한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 3: 문서 갱신 + 최종 전체 검증

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md` (진행 상태 갱신)

**Interfaces:** 없음 (문서 전용)

- [ ] **Step 1: architecture.md notify 섹션 갱신**

`docs/agents/architecture.md`의 `adapter/out/`, `adapter/in/`, `domain/port/out/` 설명에서 `adapter/out/notify`, `adapter/in/telegram`, `domain/port/out/{NotifyPort,UserNotificationPort,FcmDeviceTokenPort,TelegramBotInfoPort}`를 참조하는 기존 서술을 `com.kista.notify.adapter.out.gateway`, `com.kista.notify.adapter.in.telegram`, `com.kista.notify.domain.port.out.*` 로 갱신. finance 이전 때 추가된 서술(commit a0ee1a30) 바로 아래에 notify 단락 추가 — 구조는 finance 단락과 동일한 톤(패키지 요약 + "이미 모듈로 이전됨" 명시).

- [ ] **Step 2: constraints.md 갱신**

FcmDeviceToken 삭제로 인한 영향이 있는지 확인(현재 constraints.md에 FcmDeviceToken을 언급하는 절이 없으면 스킵). notify 모듈 이전 관련 새 제약이 생겼다면(예: gateway/persistence 패키지명 규칙) 짧게 추가.

```bash
grep -n "FcmDeviceToken\|adapter/out/notify\|adapter/in/telegram" docs/agents/constraints.md
```
결과가 있으면 그 라인들을 새 경로로 갱신, 없으면 이 스텝은 스킵.

- [ ] **Step 3: CLAUDE.md 갱신**

`CLAUDE.md`에 finance 이전 관련 서술이 있던 자리(commit a0ee1a30에서 수정된 라인)를 확인하고, notify도 이전 완료됐음을 동일한 형식으로 반영.

```bash
git show a0ee1a30 -- CLAUDE.md
```
위 diff를 참고해 동일 위치에 notify 이전 완료 사실 반영.

- [ ] **Step 4: 상위 스펙 문서 진행 상태 갱신**

`docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`의 "이전 전략 → 순서" 절 옆에 `finance✅ → notify✅ → broker/kis/toss → trading` 로 진행 상태를 갱신(메모리에 이미 기록된 형식과 동일하게).

- [ ] **Step 5: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 테스트 특정 후 수정.

- [ ] **Step 6: 커밋**

```bash
git add docs/agents/architecture.md docs/agents/constraints.md CLAUDE.md docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
git commit -m "$(cat <<'EOF'
docs(modulith): notify 모듈 이전 반영 — architecture/constraints/CLAUDE.md/스펙 갱신

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

## 리팩토링 관찰 체크포인트 (모든 태스크 공통)

각 태스크 실행 중 스펙에 없는 개선 지점(우아하지 않은 코드, 중복, 더 단순한 구현 가능성 등)을 발견하면:
1. **임의로 고치지 않는다** — 이 계획의 스코프 밖 변경은 사용자 승인 필요
2. 발견 즉시 사용자에게 짧게 보고(무엇을, 어디서, 왜 개선 여지가 있는지)
3. 스펙 문서의 "리팩토링 관찰" 섹션에 기록(사용자 승인 시)

이미 이 계획에 반영된 항목(FcmDeviceToken 삭제, gateway 명명)은 예외 — 브레인스토밍 단계에서 이미 승인됨.
