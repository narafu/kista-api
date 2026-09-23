## com.kista.account (`:trading-core`)

com.kista.account/   ← Spring Modulith 모듈(CLOSED, `:trading-core`) — 계좌 자격증명·브로커 연결. "domain"·"usecase"·"port"·"event" 4개 NamedInterface, service·adapter internal
  domain/model/       ← Account/RegisterAccountCommand/UpdateAccountCommand. `Account.Broker` nested enum 없음 — `sharedkernel.Broker` 참조. `SellableQuantity`/브로커 자격증명 예외는 broker 소유가 SSOT
  application/usecase/ ← AccountUseCase(조회/등록/수정/삭제/증권사 연결 테스트)
  application/port/output/ ← AccountPort. `BrokerEnabledPort`는 sharedkernel 소유(admin `RuntimeSettingsService`가 구현, account는 소비만)
  application/event/  ← AccountDeletedEvent(UUID accountId) — `AccountService.delete()`가 `StrategyPort.deleteByAccountId()`를 직접 호출하지 않고 발행, `trading.AccountCascadeListener`가 AFTER_COMMIT 구독(EPR 추적)
  application/service/ ← internal — AccountService(등록/수정/삭제/연결테스트)/AccountUserCascadeListener(sharedkernel `UserDeletedEvent` 구독 → `AccountPort.deleteByUserId` 소프트 삭제; AFTER_COMMIT + `fallbackExecution=true` + `@Transactional(REQUIRES_NEW)`)
  adapter/in/web/     ← internal — AccountController + dto/(AccountRequest/AccountResponse/TestConnectionRequest) + AccountInternalController(`/api/internal/accounts` — admin `AccountQueryHttpAdapter`가 소비하는 읽기 전용). **`Account`를 그대로 반환하지 않는다** — appKey/secretKey(복호화된 자격증명)가 내부망 응답에 실리지 않도록 `AdminAccountView`와 byte-identical한 5필드 own-type `AccountInternalResponse`(컨트롤러 내부 record)로 매핑
  adapter/out/persistence/ ← AccountEntity + AccountJpaRepository + AccountPersistenceAdapter

### Account ↔ Strategy 분리
`Account`(이 모듈)와 `Strategy`(trading 소유)는 별도 aggregate — `Account`엔 type/status/ticker/multiple/updatedAt이 없다(전략 속성은 전부 Strategy로 분리). 계좌·전략 이력 계층 상세 규칙은 `docs/agents/modules/trading.md` "Account ↔ Strategy 분리" 참고.
