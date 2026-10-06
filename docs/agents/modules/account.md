## com.kista.account (`:trading-core`)

com.kista.account/   ← Spring Modulith 모듈(CLOSED, `:trading-core`) — 계좌 자격증명·브로커 연결. "domain"·"usecase"·"port"·"event" 4개 NamedInterface, service·adapter internal
  domain/model/       ← Account/RegisterAccountCommand/UpdateAccountCommand. `Account.Broker` nested enum 없음 — `sharedkernel.Broker` 참조. `SellableQuantity`/브로커 자격증명 예외는 broker 소유가 SSOT
  application/usecase/ ← AccountUseCase(조회/등록/수정/삭제/증권사 연결 테스트)
  application/port/output/ ← AccountPort/BrokerEnabledPort(account가 정의, 정책 소유자인 trading `TradingPolicyService`가 구현 — 포트 역전)/AccountOpenOrderCancelPort(계좌 삭제 전 미체결 주문 취소 — account 정의, 주문 소유자인 trading `AccountOpenOrderCanceller`가 구현, 실패 건수>0이면 `AccountService.delete()`가 삭제 중단)
  application/event/  ← AccountDeletedEvent(UUID accountId) — `AccountService.delete()`(NOT_SUPPORTED — 증권사 취소 HTTP 뒤 `AccountDeletionWriter`가 broker_tokens 삭제·계좌 소프트 삭제·발행을 한 트랜잭션으로)가 발행, trading `application.service.support.AccountCascadeListener`가 AFTER_COMMIT 구독해 cycle_position → strategy_cycle → strategy 순으로 소프트 삭제(EPR 추적)
  application/service/ ← internal — AccountService(등록/수정/삭제/연결테스트 + `deleteAllByUserId` — 탈퇴 정리: 증권사 토큰(`broker_tokens`) 하드 삭제 후 계좌 소프트 삭제, 호출자 트랜잭션 합류. 탈퇴 이벤트 구독은 trading `UserCascadeListener`가 단일 트랜잭션으로 묶어 호출)
  adapter/in/web/     ← internal — AccountController + dto/(AccountRequest/AccountResponse/TestConnectionRequest) + AccountInternalController(`/api/internal/accounts` — admin `AccountQueryHttpAdapter`가 소비하는 읽기 전용). **`Account`를 그대로 반환하지 않는다** — appKey/secretKey(복호화된 자격증명)가 내부망 응답에 실리지 않도록 `contract.account.AccountSummaryResponse`(id/userId/accountNo/broker/createdAt 5필드)로 컨트롤러 안에서 명시 매핑
  adapter/out/persistence/ ← AccountEntity + AccountJpaRepository + AccountPersistenceAdapter

### Account ↔ Strategy 분리
`Account`(이 모듈)와 `Strategy`(trading 소유)는 별도 aggregate — `Account`엔 type/status/ticker/multiple/updatedAt이 없다(전략 속성은 전부 Strategy로 분리). 계좌·전략 이력 계층 상세 규칙은 `docs/agents/modules/trading.md` "Account ↔ Strategy 분리" 참고.
