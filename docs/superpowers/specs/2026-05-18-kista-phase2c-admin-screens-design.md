# Phase 2C Admin Screens Design

**Goal:** Admin 화면 4종 추가 — Accounts(계좌 현황), Trades(거래 내역), Audit(감사 로그), Anomalies(이상 징후) — 데스크탑 + 모바일 반응형

**Architecture:** kista-api (Hexagonal) + kista-ui (Next.js 16 App Router). 백엔드 4개 전용 엔드포인트 신규 추가 → 프론트엔드 4개 페이지 + 네비게이션 확장.

**Tech Stack:** Java 21, Spring Boot 3.4, Next.js 16, TypeScript, Tailwind CSS

---

## 범위

- **포함**: Admin Accounts/Trades/Audit/Anomalies 화면 (백엔드 + UI), AdminSidebar/AdminTopBar 네비게이션 확장
- **제외**: 필터링/검색/페이지네이션, 일괄 처리, 상세 팝업, Anomalies 알림 자동화

---

## 1. 백엔드 (kista-api)

### 1.1 AdminAccountController

**엔드포인트:** `GET /api/admin/accounts`
**반환:** `List<AdminAccountResponse>`

```java
record AdminAccountResponse(
    UUID id,
    UUID userId,
    String ownerNickname,    // User.nickname
    String accountNoMasked,  // 마지막 4자리만 노출: "****1234"
    String ticker,           // Ticker.name()
    String strategyType,     // StrategyType.name()
    String strategyStatus    // StrategyStatus.name()
) {}
```

**파일:**
- Create: `domain/port/in/AdminListAccountsUseCase.java` — `List<Account> listAll()`
- Create: `application/service/AdminAccountService.java` — UseCase 구현, AccountRepository + UserRepository 조회
- Create: `adapter/in/web/AdminAccountController.java` — GET + DTO 변환
- Create: `src/test/java/.../AdminAccountControllerTest.java` — `@WebMvcTest` 200/401 테스트

**구현 참고:**
- `AccountPersistenceAdapter.findAllByUser()` 대신 `AccountRepository.findAll()` 직접 사용
- ownerNickname: Account.userId → UserRepository.findById → nickname
- accountNoMasked: `"****" + accountNo.substring(accountNo.length() - 4)`

---

### 1.2 AdminAuditController

**엔드포인트:** `GET /api/admin/audit-logs`
**반환:** `List<AdminAuditLogResponse>`

```java
record AdminAuditLogResponse(
    UUID id,
    UUID adminId,
    String action,       // "USER_APPROVE", "USER_REJECT", "USER_ROLE_CHANGE", "USER_DELETE"
    String targetType,   // "USER"
    UUID targetId,
    String payload,      // JSONB raw string (null 허용)
    Instant createdAt
) {}
```

**파일:**
- Modify: `domain/port/out/AuditLogPort.java` — `List<AuditLog> findAll()` 추가
- Modify: `adapter/out/persistence/AuditLogPersistenceAdapter.java` — `findAll()` 구현 (최신순, 최대 100건)
- Create: `domain/port/in/AdminListAuditLogsUseCase.java` — `List<AuditLog> listAll()`
- Create: `application/service/AdminAuditService.java` — UseCase 구현, AuditLogPort 주입
- Create: `adapter/in/web/AdminAuditController.java` — GET + DTO 변환
- Create: `src/test/java/.../AdminAuditControllerTest.java`

---

### 1.3 AdminTradeController

**엔드포인트:** `GET /api/admin/trades`
**반환:** `List<AdminTradeResponse>`

```java
record AdminTradeResponse(
    UUID id,
    UUID userId,
    String ownerNickname,
    LocalDate tradeDate,
    String ticker,
    String direction,    // TradeHistory.direction
    String orderType,    // "LOC" | "MOC" | "LIMIT"
    int qty,
    BigDecimal price,
    String status        // "PLACED" | "FILLED" | "FAILED"
) {}
```

**파일:**
- Create: `domain/port/in/AdminListTradesUseCase.java` — `List<TradeHistory> listAll()`
- Create: `application/service/AdminTradeService.java` — TradeHistoryPort 주입, 최근 30일 전체 조회
- Create: `adapter/in/web/AdminTradeController.java`
- Create: `src/test/java/.../AdminTradeControllerTest.java`

**참고:** `TradeHistoryPort`에 `List<TradeHistory> findAll(LocalDate from, LocalDate to)` 메서드 추가 → `TradeHistoryPersistenceAdapter` 구현 → `AdminTradeService`에서 호출 (최근 30일 범위).

---

### 1.4 AdminAnomaliesController

**엔드포인트:** `GET /api/admin/anomalies`
**반환:** `AdminAnomaliesResponse`

```java
record AdminAnomaliesResponse(
    List<AdminTradeResponse> failedTrades,      // FAILED 상태 거래 (최근 30일)
    List<AdminAccountResponse> pausedAccounts,  // 전략 PAUSED 계좌
    List<AdminAccountResponse> inactiveAccounts // 최근 7일 거래 없는 ACTIVE 전략 계좌
) {}
```

**파일:**
- Create: `domain/port/in/AdminAnomaliesUseCase.java` — `AdminAnomalies getAnomalies()`
- Create: `domain/model/AdminAnomalies.java` — `record AdminAnomalies(List<TradeHistory> failedTrades, List<Account> pausedAccounts, List<Account> inactiveAccounts)` (도메인 타입 사용, DTO 변환은 컨트롤러에서)
- Create: `application/service/AdminAnomaliesService.java`
  - failedTrades: `TradeHistoryPort.findAll(from30days, today)` 후 status=FAILED 필터링
  - pausedAccounts: `AccountRepository.findAll()` 후 strategyStatus=PAUSED 필터링
  - inactiveAccounts: ACTIVE 전략 계좌 중 `TradeHistoryPort.findAll(from7days, today)`에 없는 accountId 소유 계좌
- Create: `adapter/in/web/AdminAnomaliesController.java`
- Create: `src/test/java/.../AdminAnomaliesControllerTest.java`

---

### 보안
모든 엔드포인트: `SecurityConfig`의 `/api/admin/**` → `hasRole("ADMIN")` 규칙으로 자동 보호 (Phase 2A에서 설정 완료).

---

## 2. 프론트엔드 (kista-ui)

### 2.1 타입 추가 (`types/admin.ts`)

```typescript
export interface AdminAccount {
  id: string
  userId: string
  ownerNickname: string
  accountNoMasked: string
  ticker: string
  strategyType: string
  strategyStatus: 'ACTIVE' | 'PAUSED'
}

export interface AdminAuditLog {
  id: string
  adminId: string
  action: string
  targetType: string
  targetId: string
  payload: string | null
  createdAt: string
}

export interface AdminTrade {
  id: string
  userId: string
  ownerNickname: string
  tradeDate: string
  ticker: string
  direction: string
  orderType: string
  qty: number
  price: string
  status: 'PLACED' | 'FILLED' | 'FAILED'
}

export interface AdminAnomalies {
  failedTrades: AdminTrade[]
  pausedAccounts: AdminAccount[]
  inactiveAccounts: AdminAccount[]
}
```

### 2.2 API 함수 추가 (`lib/api/admin.ts`)

```typescript
// Server Component용 (token 필요)
export async function listAdminAccounts(token: string): Promise<AdminAccount[]>
export async function listAdminAuditLogs(token: string): Promise<AdminAuditLog[]>
export async function listAdminTrades(token: string): Promise<AdminTrade[]>
export async function getAdminAnomalies(token: string): Promise<AdminAnomalies>
```

기존 `apiFetch(path, options, token)` 패턴 동일.

### 2.3 신규 페이지

**`app/(admin)/admin/accounts/page.tsx`** (Server Component)
- 테이블 5컬럼: 소유자 | 계좌번호 | 종목 | 전략 | 상태
- 전략 상태 배지: ACTIVE=emerald, PAUSED=amber
- 빈 상태: "계좌 없음" 메시지

**`app/(admin)/admin/audit/page.tsx`** (Server Component)
- 테이블 4컬럼: 날짜 | 관리자 ID(앞 8자) | 액션 | 대상 ID(앞 8자)
- 액션 배지: USER_APPROVE=emerald, USER_REJECT=slate, USER_ROLE_CHANGE=rose, USER_DELETE=red
- 최신순

**`app/(admin)/admin/trades/page.tsx`** (Server Component)
- 테이블 7컬럼: 날짜 | 소유자 | 종목 | 방향 | 유형 | 수량 | 상태
- 상태 배지: PLACED=slate, FILLED=emerald, FAILED=rose
- 방향 배지: BUY=emerald, SELL=slate

**`app/(admin)/admin/anomalies/page.tsx`** (Server Component)
- 3개 섹션 카드:
  - **체결 실패** — `failedTrades` 목록 (날짜, 소유자, 종목, 가격)
  - **전략 중지 계좌** — `pausedAccounts` 목록 (소유자, 계좌번호)
  - **7일 미거래 계좌** — `inactiveAccounts` 목록 (소유자, 계좌번호)
- 각 섹션: 빈 상태 시 초록 체크 + "이상 없음" 메시지

### 2.4 네비게이션 수정

**`components/admin/AdminSidebar.tsx`** — navItems 배열 확장:
```typescript
{ href: '/admin/accounts', label: '계좌', icon: CreditCard }
{ href: '/admin/trades', label: '거래 내역', icon: TrendingUp }
{ href: '/admin/audit', label: '감사 로그', icon: FileText }
{ href: '/admin/anomalies', label: '이상 징후', icon: AlertTriangle }
```

**`components/admin/AdminTopBar.tsx`** — 동일하게 탭 4개 추가

---

## 파일 변경 목록

### kista-api (신규)
- Create: `domain/port/in/AdminListAccountsUseCase.java`
- Create: `domain/port/in/AdminListAuditLogsUseCase.java`
- Create: `domain/port/in/AdminListTradesUseCase.java`
- Create: `domain/port/in/AdminAnomaliesUseCase.java`
- Create: `domain/model/AdminAnomalies.java`
- Create: `application/service/AdminAccountService.java`
- Create: `application/service/AdminAuditService.java`
- Create: `application/service/AdminTradeService.java`
- Create: `application/service/AdminAnomaliesService.java`
- Create: `adapter/in/web/AdminAccountController.java`
- Create: `adapter/in/web/AdminAuditController.java`
- Create: `adapter/in/web/AdminTradeController.java`
- Create: `adapter/in/web/AdminAnomaliesController.java`
- Create: `src/test/java/.../AdminAccountControllerTest.java`
- Create: `src/test/java/.../AdminAuditControllerTest.java`
- Create: `src/test/java/.../AdminTradeControllerTest.java`
- Create: `src/test/java/.../AdminAnomaliesControllerTest.java`
- Modify: `domain/port/out/AuditLogPort.java` (findAll 추가)
- Modify: `adapter/out/persistence/AuditLogPersistenceAdapter.java` (findAll 구현)
- Modify: `domain/port/out/TradeHistoryPort.java` (findAll(from, to) 추가)
- Modify: `adapter/out/persistence/TradeHistoryPersistenceAdapter.java` (findAll 구현)

### kista-ui (신규/수정)
- Create: `app/(admin)/admin/accounts/page.tsx`
- Create: `app/(admin)/admin/audit/page.tsx`
- Create: `app/(admin)/admin/trades/page.tsx`
- Create: `app/(admin)/admin/anomalies/page.tsx`
- Modify: `types/admin.ts`
- Modify: `lib/api/admin.ts`
- Modify: `components/admin/AdminSidebar.tsx`
- Modify: `components/admin/AdminTopBar.tsx`

---

## 완료 기준

1. `GET /api/admin/accounts` → 전체 계좌 목록 반환 (소유자 닉네임, 마스킹 계좌번호 포함)
2. `GET /api/admin/audit-logs` → audit_logs 전체 목록 최신순 반환
3. `GET /api/admin/trades` → 최근 30일 전체 거래 내역 반환
4. `GET /api/admin/anomalies` → failedTrades/pausedAccounts/inactiveAccounts 3종 반환
5. 비인증 요청 → 401, USER 역할 토큰 → 403, ADMIN 토큰 → 200
6. kista-ui 4개 페이지 렌더링 + AdminSidebar/TopBar 7개 항목 표시
7. `./gradlew test` + `npm run typecheck` + `npm run build` 전체 통과
