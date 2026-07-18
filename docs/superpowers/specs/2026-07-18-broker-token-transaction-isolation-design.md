# Broker Token Transaction Isolation Design

## Problem

KIS and Toss token cache writes currently join the transaction of the calling use case. When several trading-preview requests encounter an expired or rejected token concurrently, the account lock can be released before the surrounding transaction commits. Other requests therefore still observe the old token and issue another token. If the preview later fails, the newly issued token is rolled back with the request.

This caused Toss token replacement races and KIS `EGW00133` token-issuance rate-limit failures in production.

## Design

Keep the existing account-scoped `DoubleCheckedTokenCache` lock and make `BrokerTokenCachePort.saveToken(...)` persist in a Spring `REQUIRES_NEW` transaction. The nested transaction must commit before token issuance returns and before the account lock is released. Both invalidation writes and newly issued tokens use the same method, so they receive the same isolation guarantee.

Token reads remain ordinary repository queries. PostgreSQL's default `READ COMMITTED` isolation lets a subsequent query observe the independently committed token after it acquires the account lock.

No in-memory token cache or broker-specific retry behavior is added. KIS and Toss continue sharing the same persistence port.

## Error Handling

OAuth and broker API exceptions continue to propagate through the existing adapters and `GlobalExceptionHandler`. A failed outer use-case transaction must not roll back a successfully persisted broker token. No automatic retry beyond the existing single 401 retry is introduced.

## Testing

Add a persistence integration regression test that saves a broker token inside an outer transaction which is deliberately rolled back, then verifies from a new transaction that the token remains committed. Run focused broker-token, KIS-auth, and Toss-auth tests plus production compilation.

## Production Cleanup

Only after code verification succeeds, soft-delete the 11 active `app_error_logs` rows from `2026-07-18 14:36:14+00` through `2026-07-18 14:37:07+00` whose types are `TossApiException` or `KisApiException`. Set `deleted_at`; do not physically delete rows or touch older resolved logs.
