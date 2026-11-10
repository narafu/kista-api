# 증권사 API 키 만료 알림 확장 설계 (KIS·토스·메리츠)

## 의도

KIS·토스·메리츠 API 키도 1년이면 만료된다. 키가 만료되면 토큰 발급이 실패하고 그 계좌의 매매가 멈춘다. 지금은 업비트만 만료 30·7·0일 전에 알림을 받는다(`ApiKeyExpiryScheduler`). 이 알림을 세 증권사로 넓혀, 사용자가 만료 전에 키를 연장하거나 교체할 시간을 갖게 한다.

## 현재 상태

- `Broker.apiKeyExpiring`(boolean)이 UPBIT만 true다. 이 플래그를 쓰는 곳은 `ApiKeyExpiryScheduler.java:39`(필터)와 `MarketVocabularyTest.java:78`뿐이다.
- 만료일은 `trading.accounts.api_key_expires_at`(nullable, V10)에 저장한다. 업비트는 등록(`AccountService.register`)과 키 교체(`updateCredentials`) 때 `UpbitAuthApi.verifyAccount`가 `GET /v1/api_keys`의 `expire_at`을 읽어 채운다.
- 스케줄러는 매일 10:00 KST에 `findAll()`을 돈다. 만료일을 KST 날짜로 바꿔 남은 일수가 정확히 30·7·0일이면 `ApiKeyExpiringEvent`를 발행한다. 알림은 `TradingUserNotificationAdapter.notifyApiKeyExpiring`이 보낸다(텔레그램은 연결된 경우만, FCM은 항상).
- `AccountResponse.apiKeyExpiresAt`이 이미 응답에 나가고, kista-ui `AccountCard`·`AccountSummaryCard`·`EditAccountForm`이 이 값을 쓴다.
- 운영 계좌(2026-11-10 조회, 삭제 제외): KIS 1·TOSS 1(2026-06-15 등록), MERITZ 3(2026-10-23~24 등록). 세 브로커 모두 만료일이 null이다. 1년이 넘은 계좌는 없다.

## 만료일 출처 판단

- **증권사 API 조회**: KIS·토스·메리츠 모두 키 만료일을 조회하는 API를 찾지 못했다. 사내 문서(`kis-api.md`·`toss-api.md`·`meritz-api.md`)와 웹 검색 모두 같다. 문서의 "만료"는 전부 액세스 토큰 만료다. 그래서 이번 범위에서는 쓸 수 없다. 나중에 조회 API가 생기면 그 브로커만 업비트처럼 `verifyAccount`가 채우게 바꾼다.
- **추정(키 등록·교체 시점 + 1년)**: 기본값으로 쓴다. 다만 이 값은 위험한 쪽으로 틀린다. 키가 kista 등록보다 먼저 발급됐을 수 있어서 추정 만료일이 실제보다 늦을 수 있다. 실제 만료가 추정보다 30일 넘게 빠르면 30·7·0일 알림이 하나도 가지 않는다.
- **사용자 입력**: 추정을 바로잡는 안전장치로 함께 둔다. 사용자가 증권사 화면에서 확인한 실제 만료일을 넣으면 그 값으로 덮어쓴다. KIS처럼 같은 키를 포털에서 연장하는 경우도 이 입력으로 처리한다.

## 목표

1. KIS·TOSS·MERITZ 계좌에도 만료 30·7·0일 전 알림을 보낸다. 대상, 시각, 채널, 일수는 업비트와 같다.
2. 이 세 브로커는 계좌 등록 시 만료일을 "지금 + 1년"으로 추정해 저장한다.
3. 키 교체 시 appKey가 실제로 바뀌면 "지금 + 1년"으로 다시 계산한다. 같은 appKey를 다시 넣으면 기존 만료일을 그대로 둔다. 실제 갱신 없이 추정만 1년 밀리는 것을 막기 위해서다.
4. 사용자가 이 세 브로커의 만료일을 계좌 등록·키 교체·계좌 수정 때 직접 입력할 수 있다(선택). 입력하면 추정보다 우선한다.
5. 기존 KIS·TOSS·MERITZ 계좌는 `created_at + 1년`으로 채운다.

## 비목표

- 업비트 동작 변경. 만료일은 계속 API 값만 쓰고, API가 null을 주면 null로 둔다. +1년 폴백을 두지 않는다. 사용자 입력도 받지 않는다.
- 업비트 만료일을 주기적으로 다시 조회하는 것, V10 이전 업비트 계좌(만료일 null) backfill.
- 만료 후 반복 알림이나 30·7·0일 외의 일수.
- BINANCE·MOCK. 키 만료가 없다.
- 키 발급일을 따로 추적하는 컬럼, 만료일이 추정인지 사용자 입력인지를 기록하는 컬럼.

## 반목표 (돌아가도 실패로 보는 것)

- 사용자가 추정 만료일을 사실로 믿고 실제 만료를 놓치는 것. 그래서 알림 문구와 UI에 "실제와 다르면 고칠 수 있다"는 안내를 넣는다.
- 같은 키를 다시 넣었을 뿐인데 만료일이 1년 밀리는 것.

## 결정 사항

### Broker: 만료일 출처 enum

`boolean apiKeyExpiring`을 `com.kista.shared.kernel`의 새 enum `ApiKeyExpirySource { NONE, BROKER_API, ESTIMATED }` 필드로 바꾼다. `isApiKeyExpiring()`은 `!= NONE`인 파생 메서드로 남겨, 스케줄러 필터는 그대로 둔다.

| Broker | 출처 |
|---|---|
| UPBIT | BROKER_API |
| KIS, TOSS, MERITZ | ESTIMATED |
| MOCK, BINANCE | NONE |

추정 유효기간 1년은 상수 한 곳에 둔다. 위치는 구현자가 정한다(`Broker` 또는 account 도메인).

### 만료일 계산 (AccountService)

- 등록
  - ESTIMATED: `now(clock) + 1년`.
  - BROKER_API: `verified.apiKeyExpiresAt()`. 지금과 같다.
  - NONE: null.
- 키 교체
  - ESTIMATED: `cmd.appKey()`가 `account.appKey()`와 다르면 `now + 1년`, 같으면 기존 `account.apiKeyExpiresAt()`를 유지한다.
  - BROKER_API: 지금처럼 verified 값을 쓴다.
- 현재 시각은 `Clock` 빈에서 얻는다. 1년 더하기는 KST `ZonedDateTime`에서 `plusYears(1)` 후 `Instant`로 바꾼다(`TimeZones` 상수 사용). 윤일 처리를 위해서다.

### 사용자 입력: 기존 계좌 수정 API에 필드 추가

- `PUT /api/accounts/{id}`의 요청 DTO와 `UpdateAccountCommand`에 nullable 필드 `apiKeyExpiresOn`(`LocalDate`, KST 날짜)을 더한다.
- null이면 바꾸지 않는다. 기존 클라이언트는 이 필드를 보내지 않으니 호환된다.
- 값이 있으면 다음처럼 처리한다.
  - 브로커가 ESTIMATED가 아니면 `IllegalArgumentException`을 던진다(400). 메시지는 "이 거래소는 API 키 만료일을 직접 바꿀 수 없습니다."이다.
  - 오늘(KST)보다 이전 날짜도 `IllegalArgumentException`이다. 메시지는 "API 키 만료일은 오늘 이후여야 합니다."이다.
  - 저장값은 그 KST 날짜의 00:00을 `Instant`로 바꾼 값이다. 스케줄러가 KST 날짜로 비교하므로 날짜가 그대로 보존된다.
- 별도 엔드포인트 대신 기존 수정 API를 쓴 건 내 판단이다. UI의 `EditAccountForm`이 이미 별명 수정과 만료일 표시를 한 화면에서 하고 있어서다.
- 별명 필수 검증은 그대로 둔다. 만료일만 고칠 때도 UI는 현재 별명을 함께 보낸다(PUT 전체 갱신 의미 유지, 내 판단).
- 등록(`POST /api/accounts`, 요청 DTO `AccountRequest`는 수정과 공용)과 키 교체(`PUT /{id}/credentials`, `CredentialsRequest`·`UpdateCredentialsCommand`)도 같은 선택 필드 `apiKeyExpiresOn`을 받는다(사용자 결정). 키를 발급받는 순간 증권사 화면에서 실제 만료일을 볼 수 있으니 처음부터 정확한 값을 넣게 한다.
  - 값이 오면 위 "만료일 계산"의 추정·유지 규칙보다 우선한다. 키 교체에서 같은 appKey라도 값이 오면 그 날짜로 바꾼다(포털 연장 후 같은 키 재입력 경로).
  - 검증(ESTIMATED 브로커만, 오늘 이후)과 KST 날짜→`Instant` 변환은 세 경로가 한 헬퍼를 쓴다. 키 교체에서는 증권사 검증(`verifyAccount`) 전에 이 검증을 먼저 해, 잘못된 날짜로 외부 호출을 하지 않는다.

### 알림 문구

- ESTIMATED 브로커 알림에는 한 줄을 덧붙인다. 문구는 "실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다."이다.
- 추정인지 사용자 입력인지 구분하지 않고 같은 문구를 쓴다. 출처 컬럼을 두지 않기 위해서고, 어느 경우에도 틀린 말이 아니다.
- 업비트 문구는 바꾸지 않는다.

### UI가 수정 가능 여부를 아는 방법

UI는 브로커별 수정 가능 여부를 알아야 한다. `GET /api/meta`의 Broker 메타에 `apiKeyExpirySource`를 노출한다. 현재 Broker 메타가 어떤 플래그를 노출하는지 구현자가 확인해, 같은 방식으로 추가한다. 이것도 내 판단이다.

### 기존 계좌 채우기 (Flyway, trading-core)

`db/migration-trading`의 다음 번호로 한 번만 실행한다.

```sql
UPDATE trading.accounts
SET api_key_expires_at = created_at + interval '1 year'
WHERE broker IN ('KIS', 'TOSS', 'MERITZ')
  AND api_key_expires_at IS NULL;
```

- `created_at`은 키 발급일이 아니다. 키를 교체해도 바뀌지 않는다. 그래도 지금 신호 중 가장 이르고, 이른 쪽으로 틀리는 편이 안전하다. 그래서 추정치로 쓰고, 정정은 사용자 입력에 맡긴다.
- `updated_at`은 별명만 바꿔도 갱신되므로 키 교체 신호로 쓰지 않는다.
- 운영 계좌는 모두 1년 미만이라 결과가 전부 미래 날짜다. 로컬처럼 과거 날짜가 나오는 경우는 그대로 둔다. 스케줄러가 과거 날짜에는 알림을 보내지 않을 뿐이다. 응답은 과거 날짜를 그대로 내보내고, 표시 방식은 UI 작업이 정한다.
- 같은 마이그레이션(V19 예상, `migration-trading` 최신이 V18)에서 `COMMENT ON COLUMN trading.accounts.api_key_expires_at`을 새 의미로 고친다. V10의 COMMENT("업비트만 값")는 수정하지 않는다(적용된 마이그레이션 수정 금지).

## 구현자에게 맡기는 것

- 1년 상수의 위치와 이름, `ApiKeyExpirySource`의 필드 주석 문구.
- 테스트 구성. 최소한 다음은 고정한다.
  - `MarketVocabularyTest`의 출처 매핑.
  - `AccountServiceTest`: 등록 시 추정, 키 교체 시 appKey가 바뀐 경우와 같은 경우, 업비트 불변.
  - 수정 API: 거부 2종과 날짜 저장.
  - `ApiKeyExpirySchedulerTest`: KIS 계좌도 발행되는지.
  - `TradingUserNotificationAdapterWordingTest`: ESTIMATED 안내 줄.
- 문서 갱신
  - `Broker.java` 주석, `Account.java:20-21`의 만료일 필드 주석, `AccountController.java:84` 키 교체 API 설명("업비트" 한정 문구).
  - root `MetaController.BrokerMeta.from`(`:88-90`)에 출처 필드 추가.
  - `docs/agents/modules/tradingcore/account.md`의 스케줄러 항목.
  - `kis-api.md`·`toss-api.md`·`meritz-api.md`: 키 만료일 조회 API가 없어 추정으로 처리한다는 한 줄.
  - `flows.yml`의 API 키 만료 알림 흐름과 기능 항목.
  - kista-ui에 영향이 있으면 `README.md`.

## 별도 작업: kista-ui (UI 세션에서 진행)

- 계좌 카드와 상세에서 KIS·TOSS·MERITZ의 만료일도 표시한다. 기존 표시 조건이 업비트로 한정돼 있는지 확인한다.
- ESTIMATED 브로커일 때 세 화면에 선택 날짜 입력을 보여주고 `apiKeyExpiresOn`으로 보낸다: 계좌 등록 폼, 키 교체 폼, `EditAccountForm`. 안내 문구는 "증권사에서 확인한 실제 만료일을 입력하세요. 비우면 오늘부터 1년으로 계산합니다"로 한다(수정 폼은 뒷문장 제외).
- `api-types.ts`와 `openapi.json`을 갱신한다.
