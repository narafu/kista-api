# 클린코드 전역 리팩토링 구현 계획 (kista-api + kista-ui, 2차 검토 사이클)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026-07-11 2차 4렌즈 검토에서 확인·검증된 클린코드 문제를 **동작 불변** 전제로 수정한다 — 죽은 코드 제거, 일관성 통일, FSD 레이어 위반 해소, CI 정합성 복구.

**Architecture:** 모든 발견은 오케스트레이터가 실제 코드로 직접 검증 완료. 죽은 코드 제거(호출처 0건 확인됨)와 잔재 정리를 먼저, 구조 이동(FSD)을 다음, 테스트 인프라 개선을 마지막에 수행한다. 매매 공식·주문 생성 로직은 로직 불변 — VR 상수화(Task 6)만 값 동일성 테스트로 보증.

**Tech Stack:** Java 21 + Spring Boot 3 (kista-api, Fly.io) / Next.js 16 App Router + FSD (kista-ui, Vercel)

## Global Constraints

- kista-api 디렉토리: `/Users/phs/workspace/kista/kista-api` / kista-ui 디렉토리: `/Users/phs/workspace/kista/kista-ui` — **서로 독립 git 저장소, 각자 커밋**
- 커밋 전 확인: `git config user.name` = `narafu`, `git config user.email` = `narafu@kakao.com`
- 커밋 메시지 한글 + Conventional Commit (`refactor:`, `fix:`, `chore:`, `docs:`, `test:`)
- **`git push` 금지** — 사용자가 명시 요청할 때만 (push = 즉시 운영 배포)
- bash에서 gradle: `bash gradlew <task>` (`./gradlew` 아님)
- Java 파일 수정 시 BOM(`\xef\xbb\xbf`) 삽입 금지 — 수정 직후 `bash gradlew compileJava`로 검증
- **매매 공식·주문 생성 로직(`InfiniteStrategy`, `PrivacyStrategy`, `VrStrategy`, `InfinitePosition`, `VrPosition`) 절대 수정 금지** (Task 6의 `StrategyVrDetail` 상수화는 값 동일 — 예외 승인됨)
- kista-api 테스트 실패 진단: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`
- kista-ui 검증: `npm run typecheck && npm run test` (lint는 신뢰 불가 — 사용 금지)
- 주석 규칙(api): `//` 인라인만, Javadoc·블록 주석 금지

---

## 오케스트레이션·모델 라우팅

- **오케스트레이터**: Sonnet — superpowers:subagent-driven-development로 Task별 신규 서브에이전트 디스패치
- **구현 서브에이전트**:

| Task | 내용 | 레포 | 모델 | 리뷰 게이트 |
|------|------|------|------|------------|
| 1 | (UI) CI 정합성 복구 — Node 22, lint→typecheck, Supabase 잔재 | kista-ui | **Haiku** | 권장 (배포 파이프라인) |
| 2 | (API) 죽은 코드·잔재 일괄 제거 6건 | kista-api | **Haiku** | 불필요 (컴파일+테스트 통과로 갈음) |
| 3 | (UI) FSD 레이어 위반 해소 3건 | kista-ui | **Sonnet** | 필수 (구조 이동) |
| 4 | (API) 일관성 정리 — 가시성·로그·userMap 헬퍼 | kista-api | **Haiku** | 불필요 |
| 5 | (API) AdminTradeController accountId 소유권 검증 (열린 질문 2 승인 시) | kista-api | **Sonnet** | 권장 (동작 추가) |
| 6 | (API) StrategyVrDetail BigDecimal 상수화 | kista-api | **Haiku** | 필수 (VR 공식 인접 — 값 동일성 확인) |
| 7 | (API) 테스트 fixture 중앙화 (DomainFixtures) | kista-api | **Sonnet** | 권장 |
| 8 | (UI) 소소 정리 — 죽은 export, 로컬 잔재 | kista-ui | **Haiku** | 불필요 |
| 9 | (UI) 문서 드리프트 정정 3건 | kista-ui | **Haiku** | 불필요 |

- **리뷰 서브에이전트**: Sonnet — Task 3은 "typecheck+test 통과 + 이동 후 원본 파일 삭제 확인 + import 잔재 grep 0건", Task 6은 "테스트가 0.75/0.50/0.25 값 동일성을 실제 검증하는지" 확인
- 실행 순서: Task 1→2는 즉시 병렬 가능(레포 다름). Task 3·8·9는 같은 레포지만 파일 겹침 없음 — 순차 권장. Task 4·5·6·7은 kista-api 순차 (2 완료 후)

---

## ⚠️ 열린 질문 (실행 전 사용자 답변 필요 — 답 없으면 각 기본값으로 진행)

1. **[Task 5 전제] AdminTradeController `listStrategyTradeDates`의 미사용 `accountId` 경로 파라미터** — 현재 경로 `/accounts/{accountId}/strategies/{strategyId}/trade-dates`에서 accountId를 받기만 하고 검증에 안 씁니다. ADMIN 전용이라 보안 취약점은 아니지만 잘못된 accountId로 호출해도 응답이 나옵니다. **기본값: 소유권 검증 추가(Task 5 실행)** — strategy.accountId ≠ 경로 accountId면 404. 검증이 불필요하다고 판단하면 Task 5 스킵 지시.
2. **`AccountStatisticsService.getPresentBalance()`에 `BrokerCallGuard.wrap()` 미적용 (발견됐지만 이번 계획에서 제외)** — 같은 클래스의 getMargin/getPrices는 wrap 적용(→IllegalStateException→400), getPresentBalance만 미적용(KisApiException→503). 일관성 위반이지만 wrap 적용 시 **HTTP 응답 코드가 503→400으로 변경**되어 동작 불변 원칙에 위배됩니다. kista-ui의 503 처리 로직 확인이 선행돼야 하므로 **기본값: 이번 사이클 제외**. 통일을 원하면 별도 지시(UI 대조 포함).
3. **[Task 7 범위] 테스트 fixture 중앙화** — User/Account 생성 복붙이 21개 테스트 파일에 산재. 전량 마이그레이션은 회귀 위험 대비 이득이 낮아 **기본값: `support/DomainFixtures` 신설 + 중복이 심한 5개 파일만 마이그레이션**, 나머지는 신규/수정 시 점진 적용. 전량 원하면 별도 지시.
4. **kista-api CI 워크플로우 테스트 셋업 중복 (제외)** — ci.yml과 fly-deploy.yml의 DB 생성+테스트 스텝이 중복이나, 배포 가드(매매 시간대 차단)가 걸린 민감 파일이라 **기본값: 이번 사이클 제외**, 다음 사이클 후보로만 기록.
5. **`useStrategyForm` useReducer 재설계 (제외)** — react-doctor disable 주석 7곳의 근본 해소는 전략 등록 핵심 폼 전면 재설계라 **기본값: 이번 사이클 제외**, 다음 사이클 후보.

### 검토됐지만 기각된 항목 (재론 방지용 기록)

- **`@WebMvcTest`의 `@Execution(SAME_THREAD)` 제거** — 기각. `docs/agents/testing.md`가 병렬 실행 시 mock 오염 때문에 필수로 명시한 프로젝트 규칙.
- **`VrSettingsSection` 파생 상태 useEffect 제거** — 기각. `recurringAmount=0`일 때 사용자가 '적립' 모드를 선택한 UI 상태를 유지하는 의도적 로컬 state — 순수 파생(useMemo)으로 바꾸면 금액 입력 전 모드 선택이 풀리는 동작 변경 발생.
- **widgets 간 cross-import 11건 코드 수정** — 기각(문서만 정정, Task 9). 공용 UI 위젯 목록이 widgets.md에 이미 존재하는 팀 관행.
- **`with*()` record 복사 메서드들** — 기각. 프로젝트 표준 불변 패턴, 전부 사용 중.
- **소유권 검증 반복** — 기각. 이미 `requireOwnedAccount`로 중앙화 완료.

---

### Task 1: (UI) CI 정합성 복구 [kista-ui / Haiku]

**Files:**
- Modify: `/Users/phs/workspace/kista/kista-ui/.github/workflows/ci.yml`

**왜:** ① CI가 Node 20 사용 — CLAUDE.md/deployment.md는 "Node 22 고정 필수(undici v8 호환, 20 다운그레이드 금지)" 명시, Dockerfile은 `node:22-alpine`. CI만 배포 환경과 불일치. ② CI가 `npm run lint` 실행 — CLAUDE.md가 "lint는 react-doctor 규칙 미정의 오류로 신뢰 불가, typecheck만 사용" 명시 → PR이 근거 없이 실패할 위험. ③ Supabase 환경변수 — 코드베이스에 Supabase 코드/의존성 0건(이전 스택 잔재).

- [ ] **Step 1: 현재 워크플로우 전문 읽기**

`.github/workflows/ci.yml` 전체를 Read로 읽고 아래 3개 위치를 확인한다 (라인 번호는 2026-07-11 기준 — 정확 위치는 파일에서 재확인):
- L19: `node-version: '20'`
- L29: `run: npm run lint`
- L34-35: `NEXT_PUBLIC_SUPABASE_URL` / `NEXT_PUBLIC_SUPABASE_ANON_KEY`

- [ ] **Step 2: 3건 수정**

```yaml
# 변경 1: node-version: '20' → '22'
          node-version: '22'
```

```yaml
# 변경 2: Lint 스텝을 typecheck로 교체 (스텝 이름도 변경)
      - name: Typecheck
        run: npm run typecheck
```
주의: 워크플로우에 이미 별도 typecheck 스텝이 있으면 lint 스텝을 **삭제**만 한다 (typecheck 중복 실행 방지).

```yaml
# 변경 3: 아래 2줄 삭제
          NEXT_PUBLIC_SUPABASE_URL: https://placeholder.supabase.co
          NEXT_PUBLIC_SUPABASE_ANON_KEY: placeholder_anon_key
```

- [ ] **Step 3: 로컬 동등 검증**

Run: `cd /Users/phs/workspace/kista/kista-ui && npm run typecheck && npm run test`
Expected: 둘 다 통과 (CI가 실행할 명령이 로컬에서 통과하는지 확인)

- [ ] **Step 4: YAML 문법 검증**

Run: `cd /Users/phs/workspace/kista/kista-ui && node -e "const yaml=require('js-yaml');yaml.load(require('fs').readFileSync('.github/workflows/ci.yml','utf8'));console.log('YAML OK')" 2>/dev/null || python3 -c "import yaml;yaml.safe_load(open('.github/workflows/ci.yml'));print('YAML OK')"`
Expected: `YAML OK`

- [ ] **Step 5: 커밋**

```bash
cd /Users/phs/workspace/kista/kista-ui
git add .github/workflows/ci.yml
git commit -m "fix(ci): Node 22 통일·lint→typecheck 교체·Supabase 잔재 env 제거 — 배포 환경과 CI 정합성 복구"
```

---

### Task 2: (API) 죽은 코드·잔재 일괄 제거 6건 [kista-api / Haiku]

**Files:**
- Delete: `src/main/java/com/kista/adapter/in/telegram/BotState.java`
- Modify: `src/main/java/com/kista/domain/model/order/Order.java` (replacementWith 삭제)
- Modify: `src/main/java/com/kista/application/service/admin/AdminReorderService.java` (3-arg 오버로드 삭제)
- Modify: `src/main/java/com/kista/adapter/in/web/AdminDashboardController.java` (미사용 adminId 파라미터 삭제)
- Modify: `src/main/java/com/kista/application/service/trading/CycleOrderComputer.java` (Optional null 방어 제거)
- Modify: `src/main/java/com/kista/domain/model/admin/AdminManualTradeCorrectionCommand.java` (죽은 validation 어노테이션 제거)
- Modify: `gradle.properties` (Windows 경로 삭제)

**근거 (오케스트레이터 검증 완료):** 모두 grep으로 호출처 0건 또는 무동작 확인됨. Command의 validation 어노테이션은 컨트롤러가 `@Valid AdminManualTradeCorrectionRequest`(web DTO, 자체 어노테이션 보유)로 검증 후 `toCommand()` 변환하므로 Command 쪽 어노테이션은 실행되지 않는 죽은 코드.

- [ ] **Step 1: BotState.java 삭제**

```bash
cd /Users/phs/workspace/kista/kista-api
rm src/main/java/com/kista/adapter/in/telegram/BotState.java
grep -rn "BotState" src/ && echo "잔재 있음 — 중단" || echo "OK"
```
Expected: `OK`

- [ ] **Step 2: Order.replacementWith() 삭제**

`Order.java`에서 아래 메서드(주석 포함)를 삭제한다 (같은 목적의 `Order.reorder()` 정적 메서드가 이미 사용 중):

```java
// 취소 후 재접수용 PLANNED 사본 — 거래일·수량·가격 교체, id·접수번호·체결 정보 초기화
public Order replacementWith(LocalDate newTradeDate, int newQuantity, BigDecimal newPrice) {
    return new Order(null, accountId, strategyCycleId, newTradeDate, ticker, orderType,
            timing, direction, newQuantity, newPrice, OrderStatus.PLANNED, null, null, null);
}
```

- [ ] **Step 3: AdminReorderService 3-arg 오버로드 삭제**

`AdminReorderService.java` L61-63의 아래 메서드를 삭제한다 (프로덕션은 2-arg, 테스트는 4-arg만 호출 — 3-arg 호출처 0건):

```java
AdminReorderResult reorder(UUID adminId, AdminReorderCommand command, DstInfo dst) {
    return reorder(adminId, command, dst, Instant.now());
}
```
주의: 2-arg 버전(L55)이 이 3-arg를 경유한다면 4-arg 직접 호출로 변경:
```java
public AdminReorderResult reorder(UUID adminId, AdminReorderCommand command) {
    DstInfo dst = DstInfo.calculate();
    return reorder(adminId, command, dst, Instant.now());
}
```

- [ ] **Step 4: AdminDashboardController 미사용 adminId 파라미터 삭제**

```java
// 변경 전
public AdminDashboardResponse getStats(@AuthenticationPrincipal UUID adminId) {
    return AdminDashboardResponse.from(adminQuery.getStats());
}
// 변경 후 (미사용 import: AuthenticationPrincipal, UUID도 다른 사용처 없으면 함께 제거)
public AdminDashboardResponse getStats() {
    return AdminDashboardResponse.from(adminQuery.getStats());
}
```
대응 테스트(`AdminDashboardControllerTest` 존재 시)의 인증 세팅은 그대로 둔다 — SecurityConfig의 `hasRole("ADMIN")` 검증은 유지되므로.

- [ ] **Step 5: CycleOrderComputer 불필요 null 방어 제거**

`CycleOrderComputer.java` L130-133 (Optional은 null일 수 없음):

```java
// 변경 전
Optional<StrategyCycle> firstCycle = strategyCyclePort.findFirstByStrategyId(currentCycle.strategyId());
if (firstCycle == null) return false;
return firstCycle
        .map(cycle -> cycle.id().equals(currentCycle.id()))
        .orElse(false);
// 변경 후
return strategyCyclePort.findFirstByStrategyId(currentCycle.strategyId())
        .map(cycle -> cycle.id().equals(currentCycle.id()))
        .orElse(false);
```
미사용이 된 `Optional`/`StrategyCycle` import는 다른 사용처 없을 때만 제거.

- [ ] **Step 6: AdminManualTradeCorrectionCommand 죽은 어노테이션 제거**

```java
// 변경 후 전문 (jakarta.validation import 4건 모두 삭제)
public record AdminManualTradeCorrectionCommand(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        List<Fill> fills
) {
    // 개별 체결 명세
    public record Fill(
            LocalDate tradeDateKst,
            Order.OrderDirection direction,
            int quantity,
            BigDecimal price,
            String externalOrderId,
            String memo
    ) {}
}
```
주의: 기존 필드 순서·타입은 그대로 유지 (파일을 먼저 읽고 어노테이션만 제거). `quantity`가 기존에 `int`면 `int` 유지, `Integer`면 `Integer` 유지.

- [ ] **Step 7: gradle.properties Windows 경로 삭제**

```
# 아래 1줄 삭제 (특정 개발자 로컬 경로 — macOS/Linux에서 무의미)
org.gradle.java.installations.paths=C:/Users/USER/.jdks/temurin-21.0.11
```

- [ ] **Step 8: 컴파일 + 전체 테스트**

Run: `cd /Users/phs/workspace/kista/kista-api && bash gradlew compileJava compileTestJava && bash gradlew test`
Expected: BUILD SUCCESSFUL. 실패 시: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`
사전 조건: `docker compose up -d postgres` (테스트 DB)

- [ ] **Step 9: 커밋**

```bash
cd /Users/phs/workspace/kista/kista-api
git add -A
git commit -m "refactor: 죽은 코드 일괄 제거 — 미사용 enum·메서드·파라미터·validation 어노테이션·플랫폼 경로 잔재"
```

---

### Task 3: (UI) FSD 레이어 위반 해소 3건 [kista-ui / Sonnet]

**Files:**
- Delete: `shared/lib/cache/cached-api.ts` → 분할 이동
- Create: `entities/account/api/cached.ts`, `entities/strategy/api/cached.ts`, `entities/user/api/cached.ts`
- Modify: `app/(main)/dashboard/page.tsx`, `app/(main)/accounts/page.tsx`, `app/(main)/strategies/page.tsx`, `app/(main)/settings/page.tsx` (import 경로)
- Modify: `entities/strategy/api/index.ts` (PlacedOrder import 경로), `entities/order/model/types.ts`, 신규 `shared/lib/api-types` 또는 기존 shared 타입 파일
- Move: `widgets/stepper/` → `shared/ui/stepper/`, `widgets/percent-gauge/` → `shared/ui/percent-gauge/`
- Modify: `features/account/create-account/CreateAccountStepper.tsx`, `features/strategy/create-strategy/sections/UsageRatioSection.tsx` (import 경로)

**왜:** 문서화된 FSD 규칙의 명시적 위반 3건 (오케스트레이터 검증 완료):
- `shared/lib/cache/cached-api.ts`가 `@entities/{account,strategy,user}` import — shared.md "shared에서 entities import 금지" 위반
- `entities/strategy/api/index.ts:4`가 `@entities/order/model/types` import — entities.md "entities끼리 직접 참조 금지" 위반
- features 2개 파일이 `@widgets/stepper`, `@widgets/percent-gauge` import — features.md "entities/shared만 import 가능" 위반. Stepper/PercentGauge/SeedAmountInput은 도메인 지식 없는 순수 UI라 shared/ui가 올바른 위치

**Interfaces:**
- Produces: `getCachedAccounts()` → `entities/account`, `getCachedStrategies()` → `entities/strategy`, `getCachedUser()` → `entities/user` (각 index.ts에서 re-export, 시그니처 불변)
- Produces: `PlacedOrder` 타입 → shared 레이어로 이동, `entities/order`에서 re-export (기존 소비처 무변경)

- [ ] **Step 1: 사전 확인 — 현재 구조·소비처 재확인**

```bash
cd /Users/phs/workspace/kista/kista-ui
cat shared/lib/cache/cached-api.ts shared/lib/cache/tags.ts
grep -rn "cached-api\|getCachedAccounts\|getCachedStrategies\|getCachedUser" app/ features/ widgets/ entities/ shared/ --include="*.ts" --include="*.tsx"
grep -rn "revalidateTag\|cacheTags" app/ features/ entities/ shared/ --include="*.ts" --include="*.tsx" | head -20
ls shared/ui/ 2>/dev/null || ls shared/
```
소비처가 위 4개 app 페이지 외에 더 있으면 그 파일들도 Step 3에서 함께 수정.

- [ ] **Step 2: cached-api.ts 를 도메인별로 분할**

`entities/account/api/cached.ts` (strategy/user도 동일 패턴 — 기존 cached-api.ts의 해당 함수 본문을 그대로 옮긴다, 로직 수정 금지):

```ts
import { unstable_cache } from 'next/cache'
import { listAccounts } from './index'
import { ApiError } from '@shared/lib/api-client'
import { cacheTags } from '@shared/lib/cache/tags'
import type { Account } from '../model/types'

const REVALIDATE = 300 // 5분 — 태그 무효화로 즉시 갱신 가능

// (기존 cached-api.ts의 getCachedAccounts 본문 그대로 이동)
export function getCachedAccounts(/* 기존 시그니처 유지 */) { /* 기존 본문 유지 */ }
```
- 각 entity의 `index.ts`에 `export { getCachedXxx } from './api/cached'` 추가
- `shared/lib/cache/tags.ts`는 순수 문자열 상수이므로 shared에 유지
- `shared/lib/cache/cached-api.ts` 삭제

- [ ] **Step 3: app 페이지 4개 import 교체**

```ts
// 변경 전 (예: dashboard/page.tsx)
import { getCachedAccounts, getCachedStrategies } from '@shared/lib/cache/cached-api'
// 변경 후
import { getCachedAccounts } from '@entities/account'
import { getCachedStrategies } from '@entities/strategy'
```

- [ ] **Step 4: PlacedOrder 타입을 shared로 이동**

1. `entities/order/model/types.ts`의 `PlacedOrder` 인터페이스 정의를 잘라내 `shared/lib/api-types.ts`가 아닌 **신규 파일** `shared/model/placed-order.ts`에 붙여넣는다 (api-types.ts는 openapi 자동 생성 파일이므로 수동 편집 금지 — 먼저 `head -5 shared/lib/api-types.ts`로 자동 생성 여부 확인, 자동 생성이면 신규 파일로).
2. `entities/order/model/types.ts`에서 `export type { PlacedOrder } from '@shared/model/placed-order'` 재export (기존 소비처 무변경).
3. `entities/strategy/api/index.ts:4`를 `import type { PlacedOrder } from '@shared/model/placed-order'`로 교체.

- [ ] **Step 5: Stepper/PercentGauge를 shared/ui로 이동**

```bash
cd /Users/phs/workspace/kista/kista-ui
git mv widgets/stepper shared/ui/stepper
git mv widgets/percent-gauge shared/ui/percent-gauge
```
이동 후 두 폴더 내부 파일의 상대 import가 깨지는지 확인하고, 소비처 2개 파일의 import를 교체:
```ts
import { Stepper } from '@shared/ui/stepper'
import { PercentGauge, SeedAmountInput } from '@shared/ui/percent-gauge'
```
`tsconfig.json`의 `@shared/*` path alias가 `shared/*`를 가리키는지 확인 (기존 alias 활용, 신규 alias 추가 금지).

- [ ] **Step 6: 잔재 검증**

```bash
cd /Users/phs/workspace/kista/kista-ui
grep -rn "cached-api\|@widgets/stepper\|@widgets/percent-gauge" app/ features/ widgets/ entities/ shared/ --include="*.ts" --include="*.tsx" && echo "잔재 있음" || echo "OK"
grep -rn "@entities/order" entities/strategy/ && echo "위반 잔존" || echo "OK"
grep -rn "@entities/" shared/ --include="*.ts" && echo "위반 잔존" || echo "OK"
```
Expected: 모두 `OK`

- [ ] **Step 7: typecheck + 테스트**

Run: `cd /Users/phs/workspace/kista/kista-ui && npm run typecheck && npm run test`
Expected: 통과 (SeedAmountInput.test.tsx는 폴더와 함께 이동됐으므로 그대로 실행됨)

- [ ] **Step 8: 커밋**

```bash
cd /Users/phs/workspace/kista/kista-ui
git add -A
git commit -m "refactor: FSD 레이어 위반 3건 해소 — cached-api 도메인 분할, PlacedOrder shared 이동, 순수 UI 위젯 shared/ui 이동"
```

---

### Task 4: (API) 일관성 정리 — 가시성·로그·userMap 헬퍼 [kista-api / Haiku]

**Files:**
- Modify: `src/main/java/com/kista/application/service/privacy/PrivacyTradeValidationService.java` (public → package-private)
- Modify: `src/main/java/com/kista/application/service/privacy/PrivacyService.java` (throw 전 로그)
- Create: `src/main/java/com/kista/adapter/in/web/AdminUserViews.java` (userMap 헬퍼)
- Modify: `src/main/java/com/kista/adapter/in/web/AdminAccountController.java`, `AdminObservabilityController.java`, `AdminTradeController.java`

**왜:** ① `PrivacyTradeValidationService`는 같은 패키지에서만 사용(검증 완료)되는데 public — 프로젝트 규칙 "@Service는 package-private". ② `PrivacyService`가 차단 예외를 throw하기 전 로그 없이 notify만 함 — 타 서비스와 불일치, 추적 곤란. ③ `Map<UUID, AdminUserView>` 빌드 코드가 admin 컨트롤러 3곳에 복붙.

- [ ] **Step 1: PrivacyTradeValidationService package-private 전환**

```java
// 변경 전
public class PrivacyTradeValidationService implements PrivacyTradeValidationUseCase {
// 변경 후
class PrivacyTradeValidationService implements PrivacyTradeValidationUseCase {
```
클래스 선언부만 변경. 테스트(`PrivacyServiceTest`)도 같은 패키지라 영향 없음.

- [ ] **Step 2: PrivacyService throw 전 로그 추가**

`PrivacyService.java`의 아래 블록에 log.error 1줄 추가 (`@Slf4j` 없으면 추가):

```java
if (report.hasBlockingIssues()) {
    log.error("[FIDA] 기준 매매표 저장 차단: {}", report.summary()); // 추가
    IllegalArgumentException exception = new IllegalArgumentException("[FIDA] " + report.summary());
    notifyPort.notifyError(exception);
    throw exception;
}
```

- [ ] **Step 3: AdminUserViews 헬퍼 신설**

먼저 3개 컨트롤러에서 `adminUser` 필드의 실제 타입명을 확인한다 (`grep -n "adminUser" src/main/java/com/kista/adapter/in/web/AdminAccountController.java`). 확인된 UseCase 타입으로:

```java
package com.kista.adapter.in.web;

import com.kista.domain.model.admin.AdminUserView;
// (확인된 UseCase import)

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 관리자 응답 조립용 사용자 뷰 맵 빌드 헬퍼 — 컨트롤러 3곳 중복 제거
final class AdminUserViews {

    private AdminUserViews() {}

    // 전체 사용자를 id 기준 Map으로 변환 (닉네임 등 표시 정보 결합용)
    static Map<UUID, AdminUserView> mapById(/* 확인된 UseCase 타입 */ adminUser) {
        return adminUser.listAll(null, null).stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));
    }
}
```

- [ ] **Step 4: 3개 컨트롤러에서 헬퍼 사용으로 교체**

각 컨트롤러의 아래 패턴(AdminTradeController는 private buildUserMap() 메서드 전체)을 교체:

```java
// 변경 전
Map<UUID, AdminUserView> userMap = adminUser.listAll(null, null).stream()
        .collect(Collectors.toMap(AdminUserView::id, Function.identity()));
// 변경 후
Map<UUID, AdminUserView> userMap = AdminUserViews.mapById(adminUser);
```
미사용이 된 `Collectors`/`Function` import 제거.

- [ ] **Step 5: 컴파일 + 관련 테스트**

Run: `cd /Users/phs/workspace/kista/kista-api && bash gradlew compileJava compileTestJava && bash gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
cd /Users/phs/workspace/kista/kista-api
git add -A
git commit -m "refactor: 일관성 정리 — 서비스 가시성 규칙 준수·차단 예외 로그·admin userMap 빌드 중복 제거"
```

---

### Task 5: (API) AdminTradeController accountId 소유권 검증 [kista-api / Sonnet] — 열린 질문 1 승인 시

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/AdminTradeController.java:64-67`
- Modify: 해당 조회를 처리하는 `AdminQueryService` (또는 실제 구현 서비스 — 먼저 확인)
- Test: `src/test/java/com/kista/adapter/in/web/AdminTradeControllerTest.java`

**왜:** `GET /accounts/{accountId}/strategies/{strategyId}/trade-dates`가 accountId를 무시 — 경로 계층이 거짓 정보가 됨. 검증 추가로 잘못된 조합 호출을 404로 차단.

**주의:** 이 Task는 동작 추가(불일치 시 404). 검증 로직은 컨트롤러가 아닌 **application 서비스**에 둔다 (컨트롤러 try/catch 금지 규칙, `NoSuchElementException` → 404 자동 매핑).

- [ ] **Step 1: 현재 흐름 확인**

```bash
cd /Users/phs/workspace/kista/kista-api
sed -n '55,75p' src/main/java/com/kista/adapter/in/web/AdminTradeController.java
grep -rn "listStrategyTradeDates" src/main/java --include="*.java"
```
UseCase 인터페이스와 구현 서비스 위치를 파악한다.

- [ ] **Step 2: 실패하는 테스트 작성**

`AdminTradeControllerTest`(또는 구현 서비스 테스트)에 추가 — 기존 테스트 파일의 인증/mock 패턴을 그대로 따른다 (`.with(authentication(new UsernamePasswordAuthenticationToken(ADMIN_UUID, null, List.of(...))))` 패턴, `@WithMockUser` 금지):

```java
@Test
void 다른_계좌의_전략이면_거래일_조회가_404() throws Exception {
    UUID wrongAccountId = UUID.randomUUID();
    // 서비스가 accountId 불일치 시 NoSuchElementException을 던지도록 stub
    when(adminQuery.listStrategyTradeDates(wrongAccountId, STRATEGY_ID))
            .thenThrow(new NoSuchElementException("전략이 해당 계좌에 속하지 않습니다"));

    mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/trade-dates",
                    wrongAccountId, STRATEGY_ID)
                    .with(authentication(adminAuth())))
            .andExpect(status().isNotFound());
}
```
시그니처를 `listStrategyTradeDates(UUID accountId, UUID strategyId)` 2-파라미터로 변경하는 방향. UseCase 인터페이스·기존 테스트 stub도 함께 갱신.

- [ ] **Step 3: 테스트 실패 확인**

Run: `bash gradlew test --tests '*AdminTradeControllerTest*'`
Expected: 컴파일 오류 또는 FAIL (시그니처 미변경 상태)

- [ ] **Step 4: 구현**

UseCase·서비스 시그니처에 accountId 추가, 서비스에서 검증:

```java
// AdminQueryService (실제 구현 위치에 맞춰)
public List<LocalDate> listStrategyTradeDates(UUID accountId, UUID strategyId) {
    Strategy strategy = strategyPort.findByIdOrThrow(strategyId); // 없으면 NoSuchElementException → 404
    // 경로 계층 정합성 검증 — 다른 계좌의 전략 조회 차단
    if (!strategy.accountId().equals(accountId)) {
        throw new NoSuchElementException("전략이 해당 계좌에 속하지 않습니다");
    }
    return /* 기존 조회 로직 그대로 */;
}
```

컨트롤러:
```java
@GetMapping("/accounts/{accountId}/strategies/{strategyId}/trade-dates")
public List<LocalDate> listStrategyTradeDates(
        @PathVariable UUID accountId,
        @PathVariable UUID strategyId) {
    return adminQuery.listStrategyTradeDates(accountId, strategyId);
}
```

- [ ] **Step 5: 전체 테스트**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL (기존 정상 케이스 테스트도 시그니처 갱신 후 통과)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "fix(admin): 전략 거래일 조회 시 경로 accountId 소유권 검증 — 계층 불일치 호출 404 차단"
```

- [ ] **Step 7: kista-ui 소비처 대조**

```bash
grep -rn "trade-dates" /Users/phs/workspace/kista/kista-ui --include="*.ts" --include="*.tsx" -l | grep -v node_modules
```
UI가 이 엔드포인트를 호출하면 올바른 accountId를 넘기는지 확인 (경로 형식 변경은 없으므로 대부분 무영향 — 확인만).

---

### Task 6: (API) StrategyVrDetail BigDecimal 상수화 [kista-api / Haiku]

**Files:**
- Modify: `src/main/java/com/kista/domain/model/strategy/StrategyVrDetail.java:22-24`
- Test: 기존 VR 도메인 테스트 (`bash gradlew test --tests 'com.kista.domain.*'`)

**왜:** `poolLimitRate()`가 호출마다 BigDecimal 3종을 새로 생성. `static final` 상수화 — **반환 값은 완전 동일** (VR 공식 SSOT constraints.md의 0.75/0.50/0.25 그대로).

- [ ] **Step 1: 상수 선언 + 메서드 교체**

```java
// record 본문 상단에 추가 (constraints.md "VR 공식" poolLimitRate SSOT)
private static final BigDecimal POOL_LIMIT_RATE_SAVING = new BigDecimal("0.75");   // 적립식 (recurringAmount > 0)
private static final BigDecimal POOL_LIMIT_RATE_HOLD = new BigDecimal("0.50");     // 거치식 (== 0)
private static final BigDecimal POOL_LIMIT_RATE_WITHDRAW = new BigDecimal("0.25"); // 인출식 (< 0)

public BigDecimal poolLimitRate() {
    if (recurringAmount > 0) return POOL_LIMIT_RATE_SAVING;
    if (recurringAmount == 0) return POOL_LIMIT_RATE_HOLD;
    return POOL_LIMIT_RATE_WITHDRAW;
}
```
분기 조건·비교 순서는 기존과 완전 동일하게 유지. `recurringAmount` 타입이 BigDecimal이면 기존 비교식(`compareTo` 등)을 그대로 두고 반환값만 상수로 교체.

- [ ] **Step 2: VR 테스트 실행**

Run: `cd /Users/phs/workspace/kista/kista-api && bash gradlew test --tests 'com.kista.domain.*'`
Expected: 통과 — 기존 테스트가 0.75/0.50/0.25 값을 검증. 만약 poolLimitRate 값을 직접 단언하는 테스트가 없다면 `StrategyVrDetailTest`에 3분기 값 단언 테스트를 추가 후 실행.

- [ ] **Step 3: 전체 테스트 + 커밋**

```bash
bash gradlew test
git add -A
git commit -m "refactor(domain): StrategyVrDetail poolLimitRate 상수화 — 호출마다 BigDecimal 재생성 제거 (값 불변)"
```

---

### Task 7: (API) 테스트 fixture 중앙화 [kista-api / Sonnet] — 열린 질문 3 기본값: 5개 파일

**Files:**
- Create: `src/test/java/com/kista/support/DomainFixtures.java`
- Modify: `FcmAdapterTest`, `CompositeUserNotificationAdapterTest`, `TelegramUserNotificationAdapterTest`, `TradingOpenSchedulerTest`, `TradingCloseSchedulerTest` (경로는 grep으로 확인)

**왜:** User/Account 도메인 객체 생성이 21개 테스트에 각기 다른 형태로 복붙 — record 필드 변경 시 21곳 수정 필요. 중앙 fixture로 변경 파급을 1곳으로 축소.

- [ ] **Step 1: 도메인 record 시그니처 확인**

```bash
cd /Users/phs/workspace/kista/kista-api
grep -n "public record User(" -A 15 src/main/java/com/kista/domain/model/user/User.java
grep -n "public record Account(" -A 12 src/main/java/com/kista/domain/model/account/Account.java
grep -rn "new User(" src/test/java --include="*.java" -l
```

- [ ] **Step 2: DomainFixtures 작성**

`src/test/java/com/kista/support/DomainFixtures.java` — 실제 record 시그니처에 맞춰 작성 (아래는 골격, Step 1 결과로 필드를 정확히 채울 것):

```java
package com.kista.support;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;

import java.util.UUID;

// 테스트 공용 도메인 fixture — record 필드 변경 시 이 파일만 수정
public final class DomainFixtures {

    private DomainFixtures() {}

    // 기본 ACTIVE 사용자 (필드는 User record 실제 시그니처에 맞춤)
    public static User activeUser(UUID id) {
        return new User(/* Step 1에서 확인한 필드 순서대로 합리적 기본값 */);
    }

    // 텔레그램 설정된 사용자 — 알림 어댑터 테스트용
    public static User telegramUser(UUID id, String botToken, String chatId) {
        return activeUser(id).withTelegram(botToken, chatId);
    }

    // 기본 KIS 계좌
    public static Account kisAccount(UUID id, UUID userId) {
        return new Account(/* Account record 9필드: id, userId, nickname, accountNo, appKey, secretKey, brokerAccountCode, broker, createdAt */);
    }
}
```

- [ ] **Step 3: 5개 파일 마이그레이션**

각 파일의 로컬 `user()`/`createUser()` 등 private static 헬퍼를 `DomainFixtures` 호출로 교체. **테스트가 특정 필드 값에 의존하면(예: 특정 chatId 단언) 그 값을 파라미터로 전달** — 단언 값 변경 금지.

- [ ] **Step 4: 테스트 실행**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL — 실패 시 해당 파일 마이그레이션 롤백 후 원인 파악 (단언 값 어긋남이 대부분)

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "test: 도메인 fixture 중앙화 — User/Account 생성 복붙 5개 파일 DomainFixtures로 통합"
```

---

### Task 8: (UI) 소소 정리 — 죽은 export·로컬 잔재 [kista-ui / Haiku]

**Files:**
- Modify: `entities/strategy/index.ts`, `entities/strategy/model/status-accent.ts`
- Local only (커밋 없음): 빈 디렉토리 4개, 로컬 로그 파일

- [ ] **Step 1: STRATEGY_STATUS_ACCENT 죽은 export 제거**

외부 소비처 0건 확인됨 (`strategyStatusAccent()` 함수만 소비됨):

```ts
// entities/strategy/index.ts — 변경 전
export { STRATEGY_STATUS_ACCENT, strategyStatusAccent } from './model/status-accent'
// 변경 후
export { strategyStatusAccent } from './model/status-accent'
```

```ts
// entities/strategy/model/status-accent.ts — export 키워드 제거 (파일 내부에서만 사용)
const STRATEGY_STATUS_ACCENT: Record<string, string> = {
```

- [ ] **Step 2: 로컬 잔재 삭제 (git 비추적 확인 후)**

```bash
cd /Users/phs/workspace/kista/kista-ui
git ls-files --error-unmatch firebase-debug.log 2>/dev/null && echo "추적됨 — 삭제 중단" || rm -f firebase-debug.log
rmdir entities/admin-stats entities/portfolio widgets/margin-card widgets/profit-display 2>/dev/null; true
```
`rmdir`는 빈 디렉토리만 삭제됨 — 내용이 있으면 실패하고 건너뜀 (의도된 안전장치).

- [ ] **Step 3: 검증 + 커밋**

```bash
npm run typecheck && npm run test
git add entities/strategy/
git commit -m "chore: 죽은 export 제거 및 로컬 잔재 정리 — STRATEGY_STATUS_ACCENT 내부화"
```

---

### Task 9: (UI) 문서 드리프트 정정 3건 [kista-ui / Haiku]

**Files:**
- Modify: `docs/agents/shared.md`, `docs/agents/widgets.md`, `README.md`
- (경로가 다르면 `grep -rn "shared/hooks" docs/`로 실제 위치 확인)

- [ ] **Step 1: shared.md — 미존재 디렉토리 제거**

문서의 디렉토리 트리에서 실제로 존재하지 않는 `shared/hooks/`, `shared/config/`, `shared/index.ts` 항목 삭제 (`ls shared/`로 실존 여부 재확인 후). Task 3에서 `shared/ui/stepper`, `shared/ui/percent-gauge`, `shared/model/placed-order.ts`가 추가됐다면 트리에 반영.

- [ ] **Step 2: widgets.md — cross-import 예외 명문화**

"widget 슬라이스끼리 cross-import 금지" 규칙 문단에 실태와 일치하도록 예외 추가:

```markdown
- widget 슬라이스끼리 cross-import 금지 — **단, "공용 UI 위젯" 목록(kpi-card, revealable-value, theme-toggle, page-header 등)은 다른 widget에서 import 허용** (조합 위젯의 구성 요소로 사용)
```
Task 3에서 stepper/percent-gauge가 shared/ui로 이동했으므로 공용 UI 위젯 목록에서 두 항목은 제거하고 "shared/ui로 이동됨" 반영.

- [ ] **Step 3: README.md — npm 단일화**

yarn/pnpm 명령 예시 라인을 삭제하고 npm만 남긴다 (CI·Dockerfile·package-lock.json 모두 npm 사용).

- [ ] **Step 4: 커밋**

```bash
cd /Users/phs/workspace/kista/kista-ui
git add docs/ README.md
git commit -m "docs: 문서-코드 드리프트 정정 — shared 트리 실존화·widgets cross-import 예외 명문화·npm 단일화"
```

---

## 📋 SaaS 상용화 갭 목록 (실행 Task 아님 — 다음 사이클 설계 필요 항목)

사용자가 확인한 프로젝트 방향은 "공개 SaaS 확장". 아래는 이번 검토에서 식별된 상용화 관점 누락 — 각각 별도 브레인스토밍·설계가 필요해 이번 계획에서 제외.

**상용화 인프라 (대형 — 사용자 결정 다수 필요):**
- 결제·구독 시스템 (PG 선택, 플랜 설계, 무료 체험 정책)
- 서비스 약관·개인정보처리방침 페이지 및 동의 흐름 (금융 데이터 취급 고지 포함)
- API rate limiting (사용자별 요청 제한 — 현재 KIS rate limit 429 패스스루만 존재)
- 사용자 셀프서비스 문의/지원 채널 (현재 관리자 텔레그램 봇 의존)

**온보딩·UX 갭 (UI 스캔에서 식별):**
- 신규 가입 후 PENDING 상태 안내 부재 — 승인 대기 중 무엇을 기다리는지, 예상 소요 시간 미표시
- 첫 방문자 가이드 부재 — DashboardEmpty 카드 외 온보딩 투어·툴팁 없음
- 계좌 등록 4단계 스테퍼 중간 이탈(새로고침) 시 입력값 유실
- pending/rejected 경로 전용 error.tsx 부재 — 에러 시 전체화면 리셋으로만 처리
- MarketChartCard 로딩/에러 스켈레톤 부재 (빈 차트 표시 가능성)
- 관리자 워크벤치 부분 실패 UX 미정의 (일부 API만 로드된 상태 안내 없음)
- 텔레그램 연동 해제 시 알림 채널 자동 강등 여부 UI 안내 없음 (서버 동작 확인 필요)
- FCM 미지원 브라우저(구형 iOS Safari) 사전 안내 부재

**기술 부채 (다음 사이클 후보 — 오전 사이클 후보와 통합):**
- 잔고 급변 안전장치, 브로커 서킷브레이커, CycleState sealed interface, 스케쥴러 인터럽트 시 사용자 알림 (오전 사이클에서 이월)
- `useStrategyForm` useReducer 재설계 (react-doctor disable 7곳 근본 해소)
- entities API 계층 zod 파싱 도입 (현재 `response.json() as T` 무검증 캐스팅 — 신규 엔드포인트부터 점진 적용 권장)
- `getPresentBalance` BrokerCallGuard 통일 (503→400 응답 변경 — UI 대조 선행 필요)
- kista-api CI 워크플로우 테스트 셋업 composite action 추출
- API 에러 처리 정책(throw vs 폴백) 가이드라인 shared.md 문서화

---

## 완료 후 오케스트레이터 체크리스트

- [ ] kista-api: `bash gradlew test` 전체 통과 (사전 `docker compose up -d postgres`)
- [ ] kista-ui: `npm run typecheck && npm run test` 통과
- [ ] 양 레포 `git log --oneline -10`으로 Task별 커밋 확인 (author: narafu)
- [ ] push는 하지 않음 (사용자 명시 요청 대기)
- [ ] 메모리 `project_2026_07_11_full_review.md`에 2차 사이클 완료 기록 갱신
