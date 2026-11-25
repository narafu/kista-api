## com.kista.tradingcore.account (`:trading-core`)

Spring Modulith 모듈(CLOSED) — 계좌 자격증명·브로커 연결. "domain"·"api"(AccountService 타입 단위)·"port"·"event" 4개 NamedInterface, 나머지 service·adapter internal. `Account`와 `Strategy`(trading 소유)는 별도 aggregate이며 계좌에는 전략 속성이 없다 → `trading.md` "Account ↔ Strategy 분리".

- `Account.dedupeKey()`가 전역 중복 판정 키 SSOT다 — 키 전용 브로커(`Broker.isKeyOnlyCredentials()`: 메리츠)는 appKey, 그 외 계좌번호. `account_no_hash` = HMAC(`dedupeKey()`)이고 `account_no`는 NULL 허용(메리츠).
- 포트 역전: `BrokerEnabledPort`(정책 소유자 trading `TradingPolicyService`가 구현), `AccountOpenOrderCancelPort`(주문 소유자 trading이 구현 — 실패 건수 > 0이면 `AccountService.delete()`가 삭제를 중단해 증권사에 주문이 남지 않게 한다).
- 계좌 삭제는 `AccountService.delete()`(NOT_SUPPORTED — 증권사 취소 HTTP를 트랜잭션 밖에 둔다) 뒤 `AccountDeletionWriter`가 토큰 제거·소프트 삭제·`AccountDeletedEvent` 발행을 한 트랜잭션으로 묶는다. trading `AccountCascadeListener`가 AFTER_COMMIT으로 cycle_position → strategy_cycle → strategy 순으로 소프트 삭제한다. 탈퇴 정리(`deleteAllByUserId`)는 trading `UserTradingDataEraseService.erase`가 단일 트랜잭션으로 호출한다.
- 키 교체 `updateCredentials`(NOT_SUPPORTED) 순서: 소유권 → MOCK 거부 → 메리츠 키 중복 → `verifyAccount`(Toss accountSeq 일치) → 저장·만료일 갱신 → `AccountTokenEvictionPort.evict`. 신규 등록 정책 `requireBrokerEnabled`는 적용하지 않는다. 사전 중복 검사와 저장 사이 경합의 unique 위반만 `Account.DuplicateAccountException`(409)으로 변환한다.
- `AccountInternalController`(`/api/internal/accounts`, admin이 소비하는 읽기 전용)는 `Account`를 그대로 반환하지 않는다 — 복호화된 appKey/secretKey가 내부망 응답에 실리지 않도록 `contract.account.AccountSummaryResponse` 5필드로 컨트롤러 안에서 매핑한다.
- `AccountRequest`는 register/update 공용이다. `@Valid`는 register(`AccountController` POST)에만 붙이고 update(nickname만 사용)는 `@Valid` 없이 둔다 — 등록 전용 제약(`@Pattern` 계좌번호 등)이 update에 강제되면 Breaking Change다.

### API 키 만료 점검
- `ApiKeyExpiryScheduler`(매일 10:00 KST, 락 `api-key-expiry` 30분)가 `broker.isApiKeyExpiring() && apiKeyExpiresAt != null` 계좌만 훑어 만료까지 30·7·0일 남은 날에만 `ApiKeyExpiringEvent`를 발행한다. 알림은 notify `ApiKeyExpiryNotifier` 소유.
- `GET /api/accounts/{id}`: 소유 계좌 단건(`AccountService.getOwned`). 응답 `apiKeyExpiry{status,expiresOn,daysLeft}`는 `ApiKeyExpiry.of`가 SSOT(스케줄러와 공유). 직접 입력 만료일은 KST 내일부터 받는다(당일은 남은 일수 0 = EXPIRED라 400)
- 만료일 출처 `Broker.getApiKeyExpirySource()`: TOSS·MERITZ=`ESTIMATED`(사용자 입력 `apiKeyExpiresOn` 우선, 없으면 등록·교체 시점 + 1년 KST 추정 — 등록·키 교체·수정 세 경로, 수정은 입력이 있을 때만 갱신), MOCK=`NONE`. ESTIMATED 알림에는 "실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다." 문구가 붙는다.
