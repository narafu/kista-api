# 4차 사이클 — 클린코드 리팩토링 계획 (kista-api + kista-ui)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

## Context

3차(2026-07-12 오전) 클린코드 사이클 완료 후, 사용자가 UI/UX 일관성·성능·보일러플레이트·추상화·디자인패턴·구조 리팩토링 관점을 추가한 4차 사이클을 요청. Explore 에이전트 3개(api 구조·성능·디자인패턴 / api 테스트·설정 / ui UI·성능·구조)로 재스캔 후, 오케스트레이터가 주요 발견 항목을 grep으로 직접 검증했다.

**검증 중 기각된 오탐**: `PrivacyTradeBaseOrderEntity`의 `@JoinColumn` 누락 주장 — 실제로는 이미 존재(26-27행), 스캔 에이전트 오탐으로 확인. 재제안하지 않는다.

**검증 결과 기각(과잉 추상화 판단)**: `CycleOrderComputer.compute()` 8-파라미터 메서드를 컨텍스트 객체로 감싸는 안 — 코드를 직접 읽어보니 이미 내부에서 `InfiniteInputs`/`PrivacyInputs`/`VrInputs`로 입력을 묶어 `PlanContext`를 조립하는 캡슐화가 되어 있음. 호출부 4곳(TradingService×2, ManualTradingService, TradingPreviewService)까지 건드려 파라미터 이름만 바꾸는 추가 계층은 이득 대비 리스크가 크다고 판단해 제외.

**advisor 도구 사용 불가**: 이번 세션에서 advisor 툴이 unavailable로 응답 — 대신 기존 subagent-driven-development의 태스크별 reviewer + 최종 whole-branch opus 리뷰로 "검토자 검토" 요건을 충족한다.

**실행 방식**: superpowers:subagent-driven-development — Task별 fresh implementer + task reviewer, 완료 후 whole-branch 최종 리뷰(opus). main 직접 커밋(1~3차 전례 동일).

## Global Constraints

- **동작 불변** — 기존 테스트 전체 통과로 검증 (승인된 예외 없음 — 이번 사이클은 전부 순수 리팩토링/시각적 토큰 교체)
- 매매 공식·주문 생성 로직(`InfiniteStrategy`/`PrivacyStrategy`/`VrStrategy`/`InfinitePosition`/`VrPosition`/`CycleOrderComputer` 내부 계산) 절대 변경 금지
- Java 파일 BOM(`\xef\xbb\xbf`) 삽입 금지 — 커밋 전 확인
- 커밋: 한글 메시지 + Conventional Commit 접두사, author `narafu <narafu@kakao.com>`, **push 금지**
- kista-api 검증 게이트: `./gradlew compileJava` + `./gradlew test`
- kista-ui 검증 게이트: `npm run typecheck` + `npm run test`

## 모델 라우팅

| 역할 | 모델 | 근거 |
|---|---|---|
| 오케스트레이션 | 현재 세션 | 컨텍스트 보유 |
| Task 2, 4 implementer | **haiku** | 검증된 목록의 기계적 치환 |
| Task 1, 3 implementer | **sonnet** | 다중 호출부 조율·기존 hook 재사용 판단 |
| Task reviewer (전 Task) | **sonnet** | diff 규모 소~중, 스펙 대조 중심 |
| 최종 whole-branch 리뷰 | **opus** | 양 레포 교차 검토 (advisor 대체) |

---

## Tasks (레포별 순차, 레포 간 병렬)

### Task 1 [api, sonnet]: BatchContextFactory N+1 쿼리 제거

**파일**: `src/main/java/com/kista/adapter/in/schedule/BatchContextFactory.java`

**왜**: `buildAll(List<Strategy>)` 루프 안에서 전략마다 `accountPort.findByIdOrThrow()` + `userPort.findByIdOrThrow()`를 개별 호출 (34-47행). 스케쥴러가 전체 활성 전략을 순회하므로 전략 수만큼 쿼리 2N회 발생.

**방법**: 루프 진입 전 `accountPort.findAll()` / `userPort.findAll()`을 각 1회 호출해 `Map<UUID, Account>`/`Map<UUID, User>`로 변환. 루프 내부는 `Optional.ofNullable(map.get(id)).orElseThrow(...)` 형태로 교체하되, 예외 메시지는 각 포트의 기존 `findByIdOrThrow` 기본 메서드와 동일한 문구(`"계좌를 찾을 수 없습니다: " + id`, `"사용자를 찾을 수 없습니다: " + id`)로 맞춘다. 기존 per-strategy try/catch(조회 실패 시 skip + `notifyPort.notifyError(e)`) 동작은 그대로 유지 — 배치 조회로 바뀌어도 개별 전략의 예외 처리 흐름은 변경 없음. `AccountPort`/`UserPort` 인터페이스에 새 메서드를 추가하지 말 것 — 기존 `findAll()`만 사용.

**검증**: `./gradlew compileJava` + `./gradlew test --tests 'com.kista.adapter.in.schedule.*'` + 전체 테스트.

### Task 2 [api, haiku]: Admin 소유권 검증 fetch+validate 블록 중복 제거

**파일**:
- `src/main/java/com/kista/application/service/admin/AdminSelectionChain.java`
- `src/main/java/com/kista/application/service/admin/AdminReorderService.java` (61-69행)
- `src/main/java/com/kista/application/service/admin/AdminTradeCorrectionService.java` (49-57행)

**왜**: 두 서비스가 `User user = userPort.findByIdOrThrow(...); Account account = accountPort.findByIdOrThrow(...); Strategy strategy = strategyPort.findByIdOrThrow(...); AdminSelectionChain.validate(...)` 4줄 블록을 동일하게 반복.

**방법**: `AdminSelectionChain`에 `record Selection(User user, Account account, Strategy strategy)`와 정적 메서드
```java
static Selection resolveAndValidate(UserPort userPort, AccountPort accountPort, StrategyPort strategyPort,
                                     UUID userId, UUID accountId, UUID strategyId) {
    User user = userPort.findByIdOrThrow(userId);
    Account account = accountPort.findByIdOrThrow(accountId);
    Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
    validate(user, account, strategy);
    return new Selection(user, account, strategy);
}
```
를 추가. 두 서비스의 호출부를 `AdminSelectionChain.Selection sel = AdminSelectionChain.resolveAndValidate(userPort, accountPort, strategyPort, command.userId(), command.accountId(), command.strategyId());`로 교체하고 이후 `sel.user()`/`sel.account()`/`sel.strategy()`로 참조. `AdminReorderService`의 order 인자가 있는 4-파라미터 `validate()` 오버로드는 그대로 유지(별도 sourceOrder 조회는 서비스에 남김).

**검증**: `./gradlew compileJava` + `./gradlew test --tests 'com.kista.application.service.admin.*'` + 전체 테스트.

### Task 3 [ui, sonnet]: StrategyOrderHistory 필터 중복 제거 + 주문유형 배지 센트럴화

**파일**:
- `widgets/strategy-detail/StrategyOrderHistory.tsx`
- `entities/order/model/status-badge.ts` (또는 동일 파일에 함수 추가)
- `entities/order/index.ts`

**왜**:
1. `StrategyOrderHistory.tsx`가 `shared/lib/hooks/use-range-filter-state.ts`의 `useRangeFilterState()`와 거의 동일한 `FilterState`/`FilterAction`/`filterReducer`를 로컬로 재구현(17-36행) — 유일한 차이는 `page` 필드 추가.
2. 로컬 `ORDER_TYPE_STYLE`(16-20행)이 `entities/order/model/status-badge.ts`의 `orderStatusBadgeClass`와 같은 성격(주문 유형별 배지 클래스)인데 센트럴화 안 됨.

**방법**:
1. `useRangeFilterState()`를 그대로 사용(rangeType/customFrom/customTo/pageSize)하고, `page`는 별도 `useState<number>(1)`로 분리 관리. `useEffect(() => setPage(1), [rangeType, customFrom, customTo, pageSize])`로 "필터 변경 시 1페이지로 리셋" 기존 동작을 동일하게 재현. 로컬 `FilterState`/`FilterAction`/`filterReducer` 전체 삭제, `dispatch({ type: 'range', ... })` 등 호출부를 `setRangeType(...)` 등 훅이 제공하는 setter로 교체.
2. `entities/order/model/status-badge.ts`에 `orderTypeBadgeClass(orderType: string): string` 함수를 추가(내용은 기존 `ORDER_TYPE_STYLE` 매핑 그대로 이관 + `default` 케이스로 `'bg-muted text-muted-foreground'`), `entities/order/index.ts`에서 export. `StrategyOrderHistory.tsx`는 `orderTypeBadgeClass(o.orderType)`로 교체.
3. 마크업/className은 그대로 유지 — 시각적 변화 없음.

**검증**: `npm run typecheck` + `npm run test`. 페이지네이션(범위/페이지크기 변경 시 1페이지로 리셋)이 기존과 동일하게 동작하는지 코드 리뷰로 확인(자동테스트 없으면 리뷰어가 로직 대조).

### Task 4 [ui, haiku]: 색상 하드코딩 → 시맨틱 토큰/CSS 변수 교체

**파일**:
- `widgets/strategy-detail/OrderRows.tsx`
- `widgets/fear-greed-card/FearGreedTrend.tsx`

**왜**:
1. `OrderRows.tsx`의 로컬 `directionBadgeCls`(25-28행)가 BUY에 `bg-rose-50 dark:bg-rose-950/20 text-rose-600 dark:text-rose-400`라는 하드코딩된 Tailwind 팔레트를 사용 — 프로젝트 전역에서 매수/매도는 `--pos`/`--neg` 시맨틱 토큰(`bg-pos-bg`/`text-pos`, `bg-neg-bg`/`text-neg`, `app/globals.css:133-134` "상승/매수/수익 — 빨강" / "하락/매도/손실 — 파랑")을 쓰는 게 확립된 패턴(`entities/trade/model/direction.ts`의 `directionTextClass`, `WeeklyMarketCalendar.tsx` 등). 다크모드에서 별도 `dark:` 변형을 일일이 유지보수해야 하는 문제도 있음(토큰은 CSS 변수 교체만으로 자동 대응).
2. `FearGreedTrend.tsx:44`의 `history.length === 0` 폴백 색상이 `'#9CA3AF'` 하드코딩 — `--muted-foreground` CSS 변수(`app/globals.css:100,182`)로 교체 가능.

**방법**:
1. `directionBadgeCls`를 `direction === 'BUY' ? 'bg-pos-bg text-pos' : 'bg-neg-bg text-neg'`로 교체(다크모드 변형 클래스는 토큰이 CSS 변수 기반이라 별도 `dark:` 불필요 — 제거).
2. `currentColor` 폴백값 `'#9CA3AF'` → `'var(--muted-foreground)'`로 교체 (SVG `stopColor` 속성은 CSS 변수 문자열을 그대로 받아들임 — 별도 변환 불필요).
3. `zoneOf()`가 반환하는 존별 색상(공포탐욕지수 5단계 브랜드 색)은 건드리지 않음 — 폴백 한 곳만 대상.

**검증**: `npm run typecheck` + `npm run test`. 다크모드에서 BUY/SELL 배지 색상이 다른 위젯(예: `WeeklyMarketCalendar`)과 시각적으로 일치하는지 브라우저 확인은 선택(자동 테스트로 색상 클래스명만 검증).

---

## 기각·보류 (재론 방지 기록, 다음 사이클 후보)

- **`CycleOrderComputer.compute()` 파라미터 객체화** — 기각: 이미 `PlanContext` 내부 캡슐화 있음, 추가 계층은 과잉 추상화
- **`PrivacyTradeBaseOrderEntity` `@JoinColumn` 누락** — 오탐 확인(이미 존재), 재론 불필요
- **`TradingPriceFetcher` 가격 조회 캐싱/배치 재구성** — 보류: 매매 타이밍에 영향 가능성 있어 설계 판단 필요, 대규모
- **`CycleOrderStrategy`를 persistence/execution 레이어까지 확장(`CyclePositionPersistor`/`TradingOrderExecutor`의 타입 분기 제거)** — 보류: 인터페이스 확대 + 다수 구현체 수정, 대규모 설계 판단 필요
- **React Query staleTime 전략표 문서화 및 통일** — 보류: 데이터 특성별 캐시 정책은 제품 판단 필요, 대규모
- **`CycleHistoryTable`의 12개 prop drilling 개선** — 보류: 상태 객체/context 도입 설계 판단 필요
- **Null 체크 반복(105개 파일) → Optional/Null Object 패턴 전사 적용** — 보류: 범위가 매우 넓고 리스크 큼, 장기 과제
- **`TelegramBotService`의 `StringBuilder` → stream `Collectors.joining()`** — 기각: 영향력 극히 낮음(가독성 차이 미미), 태스크로 분리할 가치 없음
- **Persistence adapter 24개의 `toDomain()`/`toEntity()` 보일러플레이트 통합** — 기각: 각 adapter가 서로 다른 필드를 매핑해 제네릭화 시 오히려 가독성 저하(record 불변값 원칙과 충돌)
- **Controller의 `.stream().map(Response::from).toList()` 반복(47곳)** — 기각: 프로젝트가 이미 채택한 관용구, DTO 변환 로직 은닉은 오히려 가독성 저하
- **테스트 fixture `UuidFixtures` 확장(55개 테스트)** — 보류: `WebMvcTestSupport`/`DomainFixtures`로 이미 상당 부분 정리됨, 추가 이득 대비 파일 55개 터치 리스크 큼 — 다음 사이클 재평가

## 실행·검증 요약

1. Task 순서: api(1→2) / ui(3→4) — 레포 내 순차, 레포 간 독립(병렬)
2. Task마다: implementer(지정 모델) → review-package → reviewer(sonnet) → 필요시 fix → ledger 기록
3. 전체 완료 후: 양 레포 최종 whole-branch 리뷰(opus, advisor 대체) + HEAD 기준 전체 테스트/typecheck 재실행
4. push는 사용자 명시 요청 시에만
