# Phase 2D Feature Completion Design

**Goal:** 회원탈퇴 백엔드, KIS API test-connection, 텔레그램 callback_query 제거 — 3개의 독립된 완성도 향상 작업

**Architecture:** kista-api (Hexagonal) + kista-ui (Next.js 16 App Router). 각 항목 독립 task로 순차 실행: 회원탈퇴 → test-connection → 텔레그램 제거.

**Tech Stack:** Java 21, Spring Boot 3.4, Next.js 16, TypeScript, Tailwind CSS

---

## 범위

- **포함**: 회원탈퇴 (백엔드 + UI), KIS test-connection (백엔드 + UI), 텔레그램 callback_query 제거
- **제외**: AccountEdit 변경이력 DB, 옵티미스틱 업데이트 (Phase 2D 후속)

---

## 1. 회원탈퇴

### 백엔드 (kista-api)

**UseCase 추가**

- `domain/port/in/DeleteMeUseCase.java` — `void deleteMe(UUID userId)`
- `UserService`에 `implements DeleteMeUseCase` 추가
  - `userRepository.delete(userId)` 호출 (`delete(UUID)` 이미 AdminService에서 사용 중)
  - cascade: accounts/kis_tokens/trade_histories/portfolio_snapshots → V16 FK CASCADE 설정 완료
  - audit_logs.admin_id → V17에서 `ON DELETE CASCADE` 설정됨
- 반환: void (204)

**컨트롤러 추가**

- `AuthController`에 `DELETE /api/auth/me` 엔드포인트 추가 (`GET /api/auth/me`와 동일 경로, 다른 HTTP 메서드)
  - `@MockBean DeleteMeUseCase` 추가 필요 (`AuthControllerTest`)
  - 204 No Content

**테스트**

- `UserServiceTest`에 `deleteMe_removesUser()` 추가
- `AuthControllerTest`에 `deleteMe_authenticated_returns204()`, `deleteMe_anonymous_returns401()` 추가

### 프론트엔드 (kista-ui)

**Route Handler**

- `app/api/auth/me/route.ts` (신규): `DELETE` handler
  - kista-api `DELETE /api/auth/me` 프록시 (bearer token 포함)
  - 성공 시 쿠키 삭제: `kista-token`, `kista-user-status`, `kista-user-role` → `maxAge: 0`
  - 패턴: `app/api/auth/logout/route.ts` 참고

**Client Component**

- `components/settings/DeleteAccountButton.tsx` (신규, `'use client'`)
  - `useState<boolean>` dialog open 상태
  - "회원 탈퇴" 버튼 클릭 → Dialog 열기
  - Dialog 내 "탈퇴 확인" 버튼 → `DELETE /api/auth/me` 호출
  - 성공 시 `window.location.href = '/'` (쿠키 삭제 후 재인증)
  - 실패 시 sonner toast "탈퇴 중 오류가 발생했습니다"

**Settings 페이지 수정**

- `app/(main)/settings/page.tsx`의 `<button disabled ...>회원 탈퇴</button>` 섹션을 `<DeleteAccountButton />`으로 교체

---

## 2. test-connection

### 백엔드 (kista-api)

**Port + Adapter (Hexagonal)**

- `domain/port/in/KisConnectionTestUseCase.java` — `boolean test(String appKey, String appSecret)`
- `domain/port/out/KisConnectionTestPort.java` — `boolean test(String appKey, String appSecret)`
- `application/service/KisConnectionTestService.java` — UseCase 구현, port.out 주입
  - KisConnectionTestPort.test() 호출 후 결과 반환
- `adapter/out/kis/KisConnectionTestAdapter.java` — port.out 구현
  - KIS OAuth 엔드포인트 `POST https://openapi.koreainvestment.com:9443/oauth2/tokenP` 직접 호출
  - body: `{ grant_type: "client_credentials", appkey: ..., appsecret: ... }`
  - 2xx 응답 시 `true`, 그 외 `false`
  - `RestTemplate`은 기존 `kisRestTemplate` 빈 재사용

**컨트롤러 추가**

- `AccountController`에 `POST /api/accounts/test-connection` 추가
  - body record: `TestConnectionRequest(String appKey, String appSecret)`
  - 응답 record: `TestConnectionResponse(boolean success, String message)`
  - 실패 시 message: `"KIS API 인증에 실패했습니다. appKey 또는 appSecret을 확인하세요."`
  - 인증 필요 (JWT Bearer, `@AuthenticationPrincipal UUID userId` — 로그 목적)
  - `@MockBean KisConnectionTestUseCase` 추가 필요 (`AccountControllerTest`)

**테스트**

- `KisConnectionTestAdapterTest` — `@ExtendWith(MockitoExtension)`, kisRestTemplate mock, 성공/실패 응답 검증
- `AccountControllerTest`에 `testConnection_success_returns200()`, `testConnection_anonymous_returns401()` 추가

### 프론트엔드 (kista-ui)

**ApiStep 수정**

- `components/accounts/steps/ApiStep.tsx`
  - `testStatus: null | 'testing' | 'ok' | 'fail'` state 추가
  - "연결 테스트" 버튼: `apiKey.length >= 10 && apiSecret.length >= 10` 일 때 활성화
  - 클릭 시: `POST /api/accounts/test-connection` (Route Handler 자동 프록시)
  - 성공(`success === true`): 초록 체크 + "연결 성공" 메시지, `testStatus = 'ok'`
  - 실패: 빨간 X + 서버 message 표시, `testStatus = 'fail'`
  - apiKey 또는 apiSecret 변경 시 `testStatus` 초기화 (`null`로 리셋)
  - "다음" 버튼: `testStatus === 'ok'`일 때만 활성화 (`valid` 조건 제거 후 대체)

**Route Handler**

- 기존 `app/api/accounts/[[...path]]/route.ts`가 `/api/accounts/test-connection` POST를 자동으로 kista-api로 프록시 → 별도 변경 불필요

---

## 3. 텔레그램 callback_query 제거

### 백엔드 (kista-api)

**TelegramBotService 수정** (`adapter/in/telegram/TelegramBotService.java`)

- `ApproveUserUseCase approveUserUseCase` 필드 삭제 (line 29)
- `handle()` 메서드의 callback_query 분기 삭제 (line 35-38):
  ```java
  // 삭제
  if (update.callbackQuery() != null) {
      handleCallbackQuery(update.callbackQuery());
      return;
  }
  ```
- `handleCallbackQuery()` 메서드 전체 삭제 (line 58-80)
- `import com.kista.domain.port.in.ApproveUserUseCase;` 삭제

**테스트 수정**

- `TelegramBotServiceTest` (있다면): callback_query 관련 테스트 케이스 삭제, `approveUserUseCase` mock 제거
- `./gradlew compileTestJava`로 빈 컴파일 검증

---

## Error Handling

| 상황 | 처리 |
|------|------|
| 회원탈퇴: DB 오류 | 500 — 탈퇴 버튼에 toast 표시 |
| test-connection: KIS API 타임아웃 | `RestTemplate` 기본 타임아웃 적용, 실패 시 `success: false` |
| test-connection: 네트워크 오류 | `RestClientException` → `success: false, message: "연결 실패"` |
| callback_query 제거 후 기존 버튼 클릭 | 텔레그램에서 로딩 스피너 무한 — 관리자(본인)가 인지하고 있으므로 허용 |

---

## 파일 변경 목록

### kista-api (신규/수정)
- Create: `src/main/java/com/kista/domain/port/in/DeleteMeUseCase.java`
- Create: `src/main/java/com/kista/domain/port/in/KisConnectionTestUseCase.java`
- Create: `src/main/java/com/kista/domain/port/out/KisConnectionTestPort.java`
- Create: `src/main/java/com/kista/application/service/KisConnectionTestService.java`
- Create: `src/main/java/com/kista/adapter/out/kis/KisConnectionTestAdapter.java`
- Create: `src/test/java/com/kista/adapter/out/kis/KisConnectionTestAdapterTest.java`
- Modify: `src/main/java/com/kista/application/service/UserService.java` (DeleteMeUseCase 추가)
- Modify: `src/main/java/com/kista/adapter/in/web/AuthController.java` (DELETE /api/auth/me + MockBean)
- Modify: `src/main/java/com/kista/adapter/in/web/AccountController.java` (POST /api/accounts/test-connection)
- Modify: `src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java` (callback_query 제거)
- Modify: `src/test/java/com/kista/application/service/UserServiceTest.java`
- Modify: `src/test/java/com/kista/adapter/in/web/AuthControllerTest.java`
- Modify: `src/test/java/com/kista/adapter/in/web/AccountControllerTest.java`
- Modify: `src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java` (있다면)

### kista-ui (신규/수정)
- Create: `app/api/auth/me/route.ts`
- Create: `components/settings/DeleteAccountButton.tsx`
- Modify: `app/(main)/settings/page.tsx` (탈퇴 버튼 교체)
- Modify: `components/accounts/steps/ApiStep.tsx` (연결 테스트 추가)

---

## 완료 기준

1. `DELETE /api/auth/me` → 204, DB에서 사용자 및 cascade 데이터 삭제 확인
2. `POST /api/accounts/test-connection` — 유효 키: `{ success: true }`, 무효 키: `{ success: false }`
3. ApiStep에서 연결 테스트 성공 후에만 "다음" 활성화
4. Settings 탈퇴 Dialog → 탈퇴 → 쿠키 삭제 → `/` 리다이렉트
5. TelegramBotService에 `ApproveUserUseCase` 의존 없음 (`./gradlew compileJava` + ArchUnit 통과)
6. `./gradlew test` 전체 통과
