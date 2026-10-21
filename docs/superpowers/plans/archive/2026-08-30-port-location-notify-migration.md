# 포트 위치 전환 — notify 청크 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `com.kista.notify.domain.port.out`(4개 포트 인터페이스)를 `com.kista.notify.application.port.output`로 이전한다. notify는 `domain/model`도 `domain/port/in`도 없는 얇은 게이트웨이 모듈이라 — 이 이동으로 `notify.domain` 트리 전체가 사라지고, `"domain"` NamedInterface는 완전히 폐지되며 `"port"` NamedInterface 하나만 신설된다(`"usecase"`는 애초에 불필요). 5개 청크 중 세 번째(레거시·finance 완료, main 병합됨 commit `c7f280df`).

**Architecture:** 레거시·finance 청크와 동일한 sed 기법 재사용. 규모가 가장 작아(4개 파일, 소비자 39개, 와일드카드 import 0건) 2태스크로 진행: **Task 1**(4개 파일 이동 + 전역 import 정합화 + compile 검증), **Task 2**(NamedInterface 재구성("domain" 폐지, "port" 신설) + ArchUnit·Modulith 검증 + 문서 갱신(모듈 루트 package-info.java 포함 — finance 청크 최종 리뷰에서 발견된 "모듈 루트 코멘트 갱신 누락" 재발 방지) + 전체 테스트 스위트 최종 검증). 로직 변경 없음.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-30-port-location-migration-design.md`

## Global Constraints

- 작업 위치: **새 worktree 생성 필요** — `EnterWorktree`(또는 `superpowers:using-git-worktrees`)로 브랜치 `worktree-port-location-notify-migration` 신규 생성 후 그 위에서 진행
- `HexagonalArchitectureTest`는 레거시 청크에서 이미 프로젝트 전역 와일드카드로 재작성됐다 — **이 청크에서 수정하지 않는다**(포트만 옮기면 자동 커버)
- **모듈 루트 `package-info.java`(`src/main/java/com/kista/notify/package-info.java`) 코멘트 갱신을 Task 2의 파일 목록에 명시적으로 포함한다** — finance 청크 최종 리뷰에서 이 파일이 태스크 목록 누락으로 stale해진 사례가 있었음(별도 fix wave로 사후 수정)
- Task 1은 `compileJava`/`compileTestJava` 통과만 검증, 전체 테스트 스위트는 Task 2 최종 1회
- 커밋 메시지: 한글, Conventional Commit 접두사, author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고**
- macOS(`darwin`) 기준 `sed -i ''` 문법 사용
- 파일 인코딩: BOM 삽입 주의

---

### Task 1: `notify/domain/port/out`(4개) → `notify/application/port/output` 이전 + 전역 import 정합화

**Files:**
- Move → `src/main/java/com/kista/notify/application/port/output/`: `FcmDeviceTokenPort.java, NotifyPort.java, TelegramBotInfoPort.java, UserNotificationPort.java`(기존 `notify/domain/port/out/*` 4개)
- Modify (import 경로 일괄 sed, 아래 Step 2): `com.kista.notify.domain.port.out.*`를 참조하는 전체 파일 — 착수 시점 기준 39개(레거시 최상위 다수 서비스/스케쥴러 + finance의 `FinanceRegistrationReminderNotifier` + trading의 스케쥴러 테스트 2건 포함, 와일드카드 import 없음 — 전부 개별 클래스 import)

**Interfaces:**
- Produces: `com.kista.notify.application.port.output.*`(4개) — Task 2에서 `"port"` NamedInterface로 공개

- [ ] **Step 0: 착수 직전 재확인**

```bash
find src/main/java/com/kista/notify/domain/port/out -name "*.java" ! -name "package-info.java" | wc -l
```
Expected: `4`.

```bash
grep -rl "import com\.kista\.notify\.domain\.port\.out\." src/main/java src/test/java | grep -v "^src/main/java/com/kista/notify/domain/port/" > /tmp/notify-port-consumers.txt
wc -l /tmp/notify-port-consumers.txt
grep -rln "import com\.kista\.notify\.domain\.port\.out\.\*;" src/main/java src/test/java
```
Expected: 39 근처, 와일드카드 0건.

- [ ] **Step 1: 4개 파일 이동 + 패키지 선언 변경**

```bash
mkdir -p src/main/java/com/kista/notify/application/port/output
git mv src/main/java/com/kista/notify/domain/port/out/FcmDeviceTokenPort.java src/main/java/com/kista/notify/application/port/output/FcmDeviceTokenPort.java
git mv src/main/java/com/kista/notify/domain/port/out/NotifyPort.java src/main/java/com/kista/notify/application/port/output/NotifyPort.java
git mv src/main/java/com/kista/notify/domain/port/out/TelegramBotInfoPort.java src/main/java/com/kista/notify/application/port/output/TelegramBotInfoPort.java
git mv src/main/java/com/kista/notify/domain/port/out/UserNotificationPort.java src/main/java/com/kista/notify/application/port/output/UserNotificationPort.java
rmdir src/main/java/com/kista/notify/domain/port/out
rmdir src/main/java/com/kista/notify/domain/port
rmdir src/main/java/com/kista/notify/domain
sed -i '' 's/^package com\.kista\.notify\.domain\.port\.out;/package com.kista.notify.application.port.output;/' src/main/java/com/kista/notify/application/port/output/*.java
```

`notify/domain`은 `port/out` 하위 4개 파일이 유일한 내용물이라(모델 없음) 이 이동으로 `notify/domain` 트리 전체가 비어 3단계 `rmdir` 전부 성공해야 정상이다. 어느 하나라도 "Directory not empty"로 실패하면 예상 밖 파일이 남아있다는 뜻 — 중단하고 확인.

- [ ] **Step 2: 전역 import 경로 일괄 치환**

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/import com\.kista\.notify\.domain\.port\.out\.\*;/import com.kista.notify.application.port.output.*;/g' \
  -e 's/import com\.kista\.notify\.domain\.port\.out\.\([A-Za-z]*\);/import com.kista.notify.application.port.output.\1;/g'
```

- [ ] **Step 3: 잔여 old-path import 재스캔**

```bash
grep -rn "import com\.kista\.notify\.domain\.port\." src/main/java src/test/java
grep -rn "com\.kista\.notify\.domain" src/main/java src/test/java
```
Expected: 둘 다 결과 없음(두 번째 명령은 `notify.domain` 네임스페이스 전체가 사라졌는지 최종 확인).

- [ ] **Step 4: 이동 확인**

```bash
find src/main/java/com/kista/notify/application/port/output -name "*.java" | wc -l
find src/main/java/com/kista/notify/domain -maxdepth 0 2>/dev/null
```
Expected: `4`, 두 번째 명령은 아무 출력 없음(디렉토리 없어짐).

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
refactor(port): notify 모듈 포트 4개를 domain/port/out에서 application/port/output으로 이전

notify/domain/port/out의 *Port 인터페이스 4개(FcmDeviceTokenPort/NotifyPort/
TelegramBotInfoPort/UserNotificationPort)를 notify/application/port/output으로
물리 이동. 이를 참조하던 39개 파일(레거시 서비스·스케쥴러 다수, finance의
FinanceRegistrationReminderNotifier, trading 스케쥴러 테스트 포함)의 import
경로를 일괄 갱신 — 로직 변경 없음. notify는 domain/model·domain/port/in이
애초에 없는 모듈이라 이 이동으로 notify/domain 트리 전체가 제거됨.
NamedInterface 재구성은 Task 2에서 처리.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01D1GHxCK7iaGfxVVb6zQvY5
EOF
)"
```

---

### Task 2: NamedInterface 재구성("domain" 폐지, "port" 신설) + ArchUnit·Modulith 검증 + 문서 갱신 + 최종 검증

**Files:**
- Delete: `src/main/java/com/kista/notify/domain/port/out/package-info.java`(Task 1에서 디렉토리째 이미 삭제됨 — 확인만)
- Create: `src/main/java/com/kista/notify/application/port/output/package-info.java`
- Modify: `src/main/java/com/kista/notify/package-info.java`(모듈 루트 — finance 청크에서 누락됐던 항목, 이번엔 명시적으로 포함)
- Modify: `docs/agents/architecture.md`, `docs/agents/constraints.md`, `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`

**Interfaces:**
- Produces: notify 모듈의 `"port"`(application.port.output) NamedInterface 1개 — `"domain"`은 완전히 폐지(대체 없음, notify에 domain 패키지 자체가 더 이상 없으므로)

- [ ] **Step 1: `application/port/output/package-info.java` 신규 작성**

`src/main/java/com/kista/notify/application/port/output/package-info.java`:

```java
// notify 모듈의 공개 계약 — *Port 접미사 출력 포트 인터페이스. "port" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("port")
package com.kista.notify.application.port.output;
```

- [ ] **Step 2: 모듈 루트 `package-info.java` 갱신**

`src/main/java/com/kista/notify/package-info.java`를 다음으로 교체:

```java
// notify 애그리게이트(Telegram/FCM 알림 발송) 모듈 — application.port.output만 공개 계약, application.service/adapter는 internal. domain 패키지 자체가 없는(모델 없음) 얇은 게이트웨이 모듈.
@org.springframework.modulith.ApplicationModule
package com.kista.notify;
```

- [ ] **Step 3: `ModulithArchitectureTest` 실행**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'
```
Expected: PASS. 실패하면 다른 모듈이 notify의 옛 `domain.port.out`(이제 없는 경로)을 여전히 참조하는 코드가 있다는 뜻 — Task 1의 import 치환 누락 가능성.

- [ ] **Step 4: `HexagonalArchitectureTest` 회귀 확인**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: PASS — 추가 수정 불필요.

- [ ] **Step 5: architecture.md notify 섹션 갱신**

`docs/agents/architecture.md`의 `com.kista.notify/` 블록 첫 줄(60번째 줄 근처)을 갱신:

```diff
- com.kista.notify/    ← Spring Modulith 2번째 이전 모듈(CLOSED) — Telegram/FCM 알림 발송 애그리게이트, 위 레거시 4패키지와 별개 최상위. domain/model·application 레이어 없이 domain/port/out(공개 계약) + adapter만 존재하는 얇은 게이트웨이 모듈(자체 UseCase 없음, 레거시 application.usecase를 그대로 소비)
+ com.kista.notify/    ← Spring Modulith 2번째 이전 모듈(CLOSED) — Telegram/FCM 알림 발송 애그리게이트, 위 레거시 4패키지와 별개 최상위. domain 패키지 자체가 없고(모델 없음) application.port.output(공개 계약, "port" NamedInterface) + adapter만 존재하는 얇은 게이트웨이 모듈(자체 UseCase 없음, 레거시 application.usecase를 그대로 소비)
```

바로 아래 `domain/port/out/` 라인을 `application/port/output/`으로 교체:

```diff
- domain/port/out/    ← NotifyPort/UserNotificationPort/FcmDeviceTokenPort/TelegramBotInfoPort — "domain" NamedInterface로 공개
+ application/port/output/ ← NotifyPort/UserNotificationPort/FcmDeviceTokenPort/TelegramBotInfoPort — "port" NamedInterface로 공개
```

"Spring Modulith 점진 도입" 단락에서 notify 서술 갱신:

```diff
- `notify`가 두 번째 이전 모듈이다(`@ApplicationModule` CLOSED, 자체 domain/model·application 없이 domain/port/out만 "domain" NamedInterface로 공개).
+ `notify`가 두 번째 이전 모듈이다(`@ApplicationModule` CLOSED, 자체 domain/model 없이 application.port.output만 "port" NamedInterface로 공개 — 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 domain/port/out을 "domain" 이름으로 공개했었다).
```

- [ ] **Step 6: constraints.md 갱신**

`docs/agents/constraints.md`의 notify 관련 3개 라인 갱신:

```diff
- notify 애그리게이트(Telegram/FCM 알림)는 `com.kista.notify`로 이미 옮겨졌다 — 신규 notify 관련 코드도 레거시 최상위가 아닌 `com.kista.notify` 안에 추가. finance와 달리 자체 domain/model·application 레이어가 없는 얇은 게이트웨이 모듈이라 `domain/port/out`(공개 계약, "domain" NamedInterface) + `adapter/{in,out}`만 구성 — UseCase가 필요하면 레거시 `application.usecase`를 그대로 참조
+ notify 애그리게이트(Telegram/FCM 알림)는 `com.kista.notify`로 이미 옮겨졌다 — 신규 notify 관련 코드도 레거시 최상위가 아닌 `com.kista.notify` 안에 추가. finance와 달리 자체 domain/model 레이어가 없는 얇은 게이트웨이 모듈이라 `application/port/output`(공개 계약, "port" NamedInterface) + `adapter/{in,out}`만 구성 — UseCase가 필요하면 레거시 `application.usecase`를 그대로 참조
```

```diff
- broker/notify 포트(`domain/port/out/*Port`)는 시그니처(파라미터·반환 타입)에 trading 타입을 직접 참조하지 않는다
+ broker/notify 포트(broker는 `domain/port/out/*Port`, notify는 `application/port/output/*Port`)는 시그니처(파라미터·반환 타입)에 trading 타입을 직접 참조하지 않는다
```

```diff
- 사용자 고유 botToken으로 Telegram API 호출이 필요하면: `com.kista.notify.domain.port.out` 포트 + `com.kista.notify.adapter.out.gateway` 어댑터 신규 생성 패턴 (예: `TelegramBotInfoPort` + `TelegramBotInfoAdapter`)
+ 사용자 고유 botToken으로 Telegram API 호출이 필요하면: `com.kista.notify.application.port.output` 포트 + `com.kista.notify.adapter.out.gateway` 어댑터 신규 생성 패턴 (예: `TelegramBotInfoPort` + `TelegramBotInfoAdapter`)
```

- [ ] **Step 7: 상위 스펙 문서 진행 상태 갱신**

`docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`의 보류 1번 항목 갱신:

```diff
- 1. 포트 위치를 domain → application(`usecase`/`port/output`)으로 전환. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요 — **착수함**(`docs/superpowers/specs/2026-08-30-port-location-migration-design.md` 참고). 레거시→finance→notify→broker→trading 5개 청크 중 레거시·finance 완료, 나머지 3개(notify/broker/trading) 진행 예정
+ 1. 포트 위치를 domain → application(`usecase`/`port/output`)으로 전환. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요 — **착수함**(`docs/superpowers/specs/2026-08-30-port-location-migration-design.md` 참고). 레거시→finance→notify→broker→trading 5개 청크 중 레거시·finance·notify 완료, 나머지 2개(broker/trading) 진행 예정
```

- [ ] **Step 8: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 결과 요약 라인(`BUILD SUCCESSFUL in Ns` 등)을 리포트에 그대로 포함할 것 — 요약 문장으로 대체하지 말 것(finance 청크 최종 리뷰에서 "증거 부족" Minor 지적을 받은 바 있음).

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/kista/notify/application/port/output/package-info.java \
        src/main/java/com/kista/notify/package-info.java \
        docs/agents/architecture.md docs/agents/constraints.md \
        docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
git status --short
git commit -m "$(cat <<'EOF'
feat(modulith): notify 모듈 NamedInterface를 "port" 하나로 재구성 + 문서 갱신

notify의 "domain" NamedInterface(domain/port/out만 병합 공개하던 것)를 Task 1의
포트 이동으로 domain 패키지 자체가 사라짐에 따라 완전히 폐지하고, "port"
(application.port.output) 하나로 대체. 모듈 루트 package-info.java도 함께
갱신(finance 청크에서 이 파일이 태스크 목록 누락으로 최종 리뷰 fix wave가
필요했던 사례의 재발 방지). ModulithArchitectureTest·HexagonalArchitectureTest
모두 통과 확인.

architecture.md/constraints.md/상위 Modulith 스펙의 notify 관련 서술을 새 구조
기준으로 갱신.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01D1GHxCK7iaGfxVVb6zQvY5
EOF
)"
```

---

## 리팩토링 관찰 체크포인트 (모든 태스크 공통)

각 태스크 실행 중 스펙에 없는 개선 지점을 발견하면:
1. **임의로 고치지 않는다** — 이 계획의 스코프 밖 변경은 사용자 승인 필요
2. 발견 즉시 사용자에게 짧게 보고

## 다음 청크

이 notify 청크가 main에 병합되면, broker 청크(15개 포트, `domain/port/out`만 — port/in 없어 `"usecase"` 불필요, 기존 `"domain"`+`"application"` 2개 NamedInterface 중 "domain"만 model-only로 축소되고 "port" 신설) 계획을 작성한다. broker/trading 두 청크의 Task 2에도 모듈 루트 package-info.java 갱신을 반드시 파일 목록에 포함할 것.
