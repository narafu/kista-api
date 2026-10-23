## com.kista.tradingcore.account (`:trading-core`)

com.kista.tradingcore.account/   ← Spring Modulith 모듈(CLOSED, `:trading-core`) — 계좌 자격증명·브로커 연결. "domain"·"usecase"·"port"·"event" 4개 NamedInterface, service·adapter internal
  domain/model/       ← Account(`apiKeyExpiresAt` — 업비트 키 만료, `dedupeKey()` — 키 전용 브로커(`Broker.isKeyOnlyCredentials()` — 업비트·메리츠·바이낸스)는 appKey, 그 외 계좌번호 = 전역 중복 판정 키 SSOT, `withCredentials`)/RegisterAccountCommand/UpdateAccountCommand. `Account.Broker` nested enum 없음 — `sharedkernel.Broker` 참조. `SellableQuantity`/브로커 자격증명 예외는 broker 소유가 SSOT
  application/usecase/ ← AccountUseCase(조회/등록/수정/삭제/증권사 연결 테스트)
  application/port/output/ ← AccountPort/BrokerEnabledPort(account가 정의, 정책 소유자인 trading `TradingPolicyService`가 구현 — 포트 역전)/AccountOpenOrderCancelPort(계좌 삭제 전 미체결 주문 취소 — account 정의, 주문 소유자인 trading `AccountOpenOrderCanceller`가 구현, 실패 건수>0이면 `AccountService.delete()`가 삭제 중단)
  application/event/  ← AccountDeletedEvent(UUID accountId) — `AccountService.delete()`(NOT_SUPPORTED — 증권사 취소 HTTP 뒤 `AccountDeletionWriter`가 증권사 토큰 제거(broker `AccountTokenEvictionPort`)·계좌 소프트 삭제·발행을 한 트랜잭션으로)가 발행, trading `application.service.support.AccountCascadeListener`가 AFTER_COMMIT 구독해 cycle_position → strategy_cycle → strategy 순으로 소프트 삭제(EPR 추적)
  application/service/ ← internal — AccountService(등록/수정/삭제/연결테스트 + `updateCredentials`(키 교체 — NOT_SUPPORTED, 소유권 → MOCK 거부 → 업비트 키 중복 → verifyAccount(KIS 저장 계좌번호·Toss accountSeq 일치) → 저장·만료일 갱신 → `AccountTokenEvictionPort.evict`(KIS broker_tokens + Toss Redis canonical). 신규 등록 정책 requireBrokerEnabled는 적용하지 않음) + `deleteAllByUserId` — 탈퇴 정리: 증권사 토큰(`AccountTokenEvictionPort` — KIS `broker_tokens` 하드 삭제·Toss Redis 무효화) 제거 후 계좌 소프트 삭제, 호출자 트랜잭션 합류. 탈퇴 이벤트 구독은 trading `UserCascadeListener`가 단일 트랜잭션으로 묶어 호출)
  adapter/in/web/     ← internal — AccountController + dto/(AccountRequest/AccountResponse/TestConnectionRequest) + AccountInternalController(`/api/internal/accounts` — admin `AccountQueryHttpAdapter`가 소비하는 읽기 전용). **`Account`를 그대로 반환하지 않는다** — appKey/secretKey(복호화된 자격증명)가 내부망 응답에 실리지 않도록 `contract.account.AccountSummaryResponse`(id/userId/accountNo/broker/createdAt 5필드)로 컨트롤러 안에서 명시 매핑
  adapter/out/persistence/ ← AccountEntity + AccountJpaRepository + AccountPersistenceAdapter — `account_no` NULL 허용(업비트), `account_no_hash` = HMAC(`dedupeKey()`), `api_key_expires_at`(V10). 업비트 등록은 `AccountRequest.accountNo`의 `@Pattern`을 그대로 두되 서비스가 값을 버려 null로 저장한다(스펙 "오면 무시"에서 수용된 편차 — 형식이 틀린 값은 400)

### Account ↔ Strategy 분리
`Account`(이 모듈)와 `Strategy`(trading 소유)는 별도 aggregate — `Account`엔 type/status/ticker/multiple/updatedAt이 없다(전략 속성은 전부 Strategy로 분리). 계좌·전략 이력 계층 상세 규칙은 `docs/agents/modules/tradingcore/trading.md` "Account ↔ Strategy 분리" 참고.

### 바이낸스 연결 테스트
`BinanceAuthApi`(`BrokerConnectionTestPort`)가 `/sapi/v1/account/apiRestrictions` 한 번으로 판정한다 — 키 오류는 호출 실패(-2015/-1022 → `BrokerCredentialException`), 응답이 오면 ① 출금 권한 켜짐 → `BrokerWithdrawalEnabledException`(422 `BROKER_WITHDRAWAL_ENABLED`) ② IP 제한 없음 → `BrokerIpNotAllowedException` ③ 읽기·현물 거래 권한 없음 → `BrokerPermissionDeniedException` 순서다. 키 만료일이 없어 `verifyAccount`는 `BrokerAccountVerification(null, null)`이다. 상세 → `docs/agents/binance-api.md`.

### 업비트 API 키 만료 점검
`adapter/in/schedule/ApiKeyExpiryScheduler`(매일 10:00 KST, 락 `api-key-expiry` 30분, `scheduler.enabled`)가 `broker.isApiKeyExpiring() && apiKeyExpiresAt != null` 계좌만 훑어 만료까지 30·7·0일 남은 날에만 `ApiKeyExpiringEvent`(application/event, userId·accountId·nickname·broker·expiresOn·daysLeft)를 발행한다 — 알림은 notify `ApiKeyExpiryNotifier`가 소유. 키 교체(`updateCredentials`) 저장 시 사전 중복 검사와 저장 사이의 경합으로 unique 위반이 나면 `DataIntegrityViolationException`을 `Account.DuplicateAccountException`(409)으로 변환한다. 관리자 계좌 응답의 `accountNoMasked`·`accountNo`는 업비트(계좌번호 없음)에서 null이다.
