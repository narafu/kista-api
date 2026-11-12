# 16. 기능 전수 조사 (엔드포인트·스케줄러·알림·기능 단위)

조사 기준: 2026-11-13, main `1db0d8db1`. 읽기 전용 측정(코드 변경 없음). 삭제·추가 판단은 하지 않고 사실과 숫자, `file:line` 근거만 적는다.
경로 약어: `API/`=`src/main/java/com/kista/api/`, `TC/`=`trading-core/src/main/java/com/kista/tradingcore/`, `SH/`=`shared/src/main/java/com/kista/shared/`. 테스트는 `src/test`, `trading-core/src/test`, `shared/src/test`(+testFixtures).
전제: 사용자가 이미 정한 것(KIS·업비트 삭제, 토스는 주문 유지, 메리츠·바이낸스 주력, fida 별도 레포)은 다시 조사하지 않았다.

## A. 엔드포인트 전수

방법: `@RestController` 57개 파일(`GlobalExceptionHandler`·`TradingExceptionHandler`는 컨트롤러 아님)에서 `@*Mapping`을 추출했다(스크립트로 클래스 base 경로 + 메서드 경로 결합). 권한은 `SecurityConfig`(`SH/platform/security/SecurityConfig.java:46-53`)·`RootSecurityPolicy`·`TradingSecurityPolicy`에서 읽었다. ui 호출처는 kista-ui 비-테스트 소스(`app features entities shared widgets components`)에서 경로 끝 정적 구간(예: `logs/audit`)을 grep한 첫 파일이다(`proxy.ts`·`app/api/*/route.ts` 프록시 선언은 제외). 일반명사 구간(`history`·`orders` 등)은 다른 경로와 겹쳐 과대 집계될 수 있다.

운영 접근 흔적: **로그 없음.** Caddy(`kista-infra-caddy-1`)에 access log가 없고(7일치 733줄이 모두 TLS·인증서 정리 로그), 앱 컨테이너도 요청 로그를 남기지 않으며(kista-api 24시간 17줄), 컨테이너 가동이 3~4시간이라 Micrometer 메트릭도 7일치가 아니다. Prometheus는 운영에 없다. 그래서 이 표에는 접근 건수 열을 두지 않았다. 사용 흔적은 D절의 테이블 행·사용자 수로 대신한다.

| # | 메서드 경로 | 컨트롤러 file:line | 프로세스 | 권한 | ui 호출처 |
|---|---|---|---|---|---|
| 1 | POST /telegram/webhook | API/admin/adapter/in/telegram/TelegramWebhookController.java:23 | root | 공개 | 호출 0 |
| 2 | GET /api/admin/accounts | API/admin/adapter/in/web/AdminAccountController.java:45 | root | ADMIN | entities/admin/api/index.ts |
| 3 | GET /api/admin/accounts/{accountId}/strategies | API/admin/adapter/in/web/AdminAccountController.java:60 | root | ADMIN | entities/admin/api/index.ts |
| 4 | PATCH /api/admin/accounts/{accountId}/strategies/{strategyId}/status | API/admin/adapter/in/web/AdminAccountController.java:68 | root | ADMIN | entities/admin/api/index.ts |
| 5 | GET /api/admin/dashboard/stats | API/admin/adapter/in/web/AdminDashboardController.java:22 | root | ADMIN | entities/admin/api/index.ts |
| 6 | GET /api/admin/logs/audit | API/admin/adapter/in/web/AdminObservabilityController.java:41 | root | ADMIN | entities/admin/api/index.ts |
| 7 | GET /api/admin/logs/errors | API/admin/adapter/in/web/AdminObservabilityController.java:54 | root | ADMIN | entities/admin/api/index.ts |
| 8 | DELETE /api/admin/logs/errors/{id} | API/admin/adapter/in/web/AdminObservabilityController.java:75 | root | ADMIN | entities/admin/api/index.ts |
| 9 | GET /api/admin/logs/anomalies | API/admin/adapter/in/web/AdminObservabilityController.java:83 | root | ADMIN | entities/admin/api/index.ts |
| 10 | GET /api/admin/_ping | API/admin/adapter/in/web/AdminPingController.java:21 | root | ADMIN | 호출 0 |
| 11 | GET /api/admin/privacy-trade-bases | API/admin/adapter/in/web/AdminPrivacyTradeController.java:43 | root | ADMIN | entities/privacy/api/index.ts |
| 12 | POST /api/admin/privacy-trade-bases | API/admin/adapter/in/web/AdminPrivacyTradeController.java:59 | root | ADMIN | entities/privacy/api/index.ts |
| 13 | PATCH /api/admin/privacy-trade-bases/{id} | API/admin/adapter/in/web/AdminPrivacyTradeController.java:71 | root | ADMIN | entities/privacy/api/index.ts |
| 14 | PATCH /api/admin/privacy-trade-bases/{baseId}/orders/{orderId} | API/admin/adapter/in/web/AdminPrivacyTradeController.java:81 | root | ADMIN | entities/order/api/index.ts |
| 15 | POST /api/admin/privacy-trade-bases/{baseId}/orders | API/admin/adapter/in/web/AdminPrivacyTradeController.java:92 | root | ADMIN | entities/order/api/index.ts |
| 16 | DELETE /api/admin/privacy-trade-bases/{baseId}/orders/{orderId} | API/admin/adapter/in/web/AdminPrivacyTradeController.java:102 | root | ADMIN | entities/order/api/index.ts |
| 17 | GET /api/admin/settings | API/admin/adapter/in/web/AdminSettingsController.java:31 | root | ADMIN | entities/admin-settings/api/index.ts |
| 18 | PUT /api/admin/settings | API/admin/adapter/in/web/AdminSettingsController.java:37 | root | ADMIN | entities/admin-settings/api/index.ts |
| 19 | GET /api/admin/trades | API/admin/adapter/in/web/AdminTradeController.java:48 | root | ADMIN | entities/admin/model/queryOptions.ts |
| 20 | GET /api/admin/accounts/{accountId}/strategies/{strategyId}/trade-dates | API/admin/adapter/in/web/AdminTradeController.java:56 | root | ADMIN | 호출 0 |
| 21 | POST /api/admin/trades/manual-fills | API/admin/adapter/in/web/AdminTradeController.java:65 | root | ADMIN | 호출 0 |
| 22 | POST /api/admin/scheduler/open | API/admin/adapter/in/web/AdminTradingSchedulerController.java:21 | root | ADMIN | 호출 0 |
| 23 | POST /api/admin/scheduler/close | API/admin/adapter/in/web/AdminTradingSchedulerController.java:28 | root | ADMIN | 호출 0 |
| 24 | GET /api/admin/users | API/admin/adapter/in/web/AdminUserController.java:32 | root | ADMIN | entities/admin/api/index.ts |
| 25 | PATCH /api/admin/users/{userId}/status | API/admin/adapter/in/web/AdminUserController.java:46 | root | ADMIN | entities/admin/api/index.ts |
| 26 | PATCH /api/admin/users/{userId}/role | API/admin/adapter/in/web/AdminUserController.java:60 | root | ADMIN | entities/admin/api/index.ts |
| 27 | DELETE /api/admin/users/{userId} | API/admin/adapter/in/web/AdminUserController.java:70 | root | ADMIN | entities/admin/api/index.ts |
| 28 | POST /api/client-errors | API/admin/adapter/in/web/ClientErrorLogController.java:28 | root | 공개 | entities/error-log/api/index.ts |
| 29 | GET /api/runtime-config | API/admin/adapter/in/web/RuntimeConfigController.java:23 | root | 공개 | entities/runtime-config/api/index.ts |
| 30 | GET /api/stats/housing-benchmark | API/benchmark/adapter/in/web/BenchmarkController.java:40 | root | 사용자 | entities/stats/api/index.ts |
| 31 | GET /api/stats/housing-benchmark/series | API/benchmark/adapter/in/web/BenchmarkController.java:77 | root | 사용자 | entities/stats/api/index.ts |
| 32 | GET /api/stats/housing-benchmark/index-series | API/benchmark/adapter/in/web/BenchmarkController.java:92 | root | 사용자 | entities/stats/api/index.ts |
| 33 | GET /api/stats/housing-benchmark/etf-series | API/benchmark/adapter/in/web/BenchmarkController.java:107 | root | 사용자 | entities/stats/api/index.ts |
| 34 | GET /api/stats/housing-benchmark/regions | API/benchmark/adapter/in/web/BenchmarkController.java:133 | root | 사용자 | entities/stats/api/index.ts |
| 35 | GET /api/admin/finance/categories | API/finance/adapter/in/web/AdminFinanceCategoryController.java:39 | root | ADMIN | entities/finance/api/index.ts |
| 36 | POST /api/admin/finance/categories | API/finance/adapter/in/web/AdminFinanceCategoryController.java:66 | root | ADMIN | entities/finance/api/index.ts |
| 37 | PUT /api/admin/finance/categories/{id} | API/finance/adapter/in/web/AdminFinanceCategoryController.java:85 | root | ADMIN | entities/finance/api/index.ts |
| 38 | DELETE /api/admin/finance/categories/{id} | API/finance/adapter/in/web/AdminFinanceCategoryController.java:102 | root | ADMIN | entities/finance/api/index.ts |
| 39 | GET /api/finance/asset-snapshots | API/finance/adapter/in/web/AssetSnapshotController.java:43 | root | 사용자 | entities/finance/api/index.ts |
| 40 | POST /api/finance/asset-snapshots | API/finance/adapter/in/web/AssetSnapshotController.java:59 | root | 사용자 | entities/finance/api/index.ts |
| 41 | PUT /api/finance/asset-snapshots/{id} | API/finance/adapter/in/web/AssetSnapshotController.java:75 | root | 사용자 | entities/finance/api/index.ts |
| 42 | PATCH /api/finance/asset-snapshots/{id}/share | API/finance/adapter/in/web/AssetSnapshotController.java:89 | root | 사용자 | entities/account/api/index.ts |
| 43 | PATCH /api/finance/asset-snapshots/{id}/unshare | API/finance/adapter/in/web/AssetSnapshotController.java:102 | root | 사용자 | entities/finance/api/index.ts |
| 44 | DELETE /api/finance/asset-snapshots/{id} | API/finance/adapter/in/web/AssetSnapshotController.java:115 | root | 사용자 | entities/finance/api/index.ts |
| 45 | GET /api/finance/accounts | API/finance/adapter/in/web/FinanceAccountController.java:32 | root | 사용자 | entities/finance/api/index.ts |
| 46 | POST /api/finance/accounts | API/finance/adapter/in/web/FinanceAccountController.java:45 | root | 사용자 | entities/finance/api/index.ts |
| 47 | PUT /api/finance/accounts/{id} | API/finance/adapter/in/web/FinanceAccountController.java:61 | root | 사용자 | entities/finance/api/index.ts |
| 48 | DELETE /api/finance/accounts/{id} | API/finance/adapter/in/web/FinanceAccountController.java:75 | root | 사용자 | entities/finance/api/index.ts |
| 49 | PATCH /api/finance/accounts/{id}/share | API/finance/adapter/in/web/FinanceAccountController.java:89 | root | 사용자 | entities/account/api/index.ts |
| 50 | PATCH /api/finance/accounts/{id}/unshare | API/finance/adapter/in/web/FinanceAccountController.java:102 | root | 사용자 | entities/finance/api/index.ts |
| 51 | GET /api/finance/budgets | API/finance/adapter/in/web/FinanceBudgetController.java:34 | root | 사용자 | entities/finance/api/index.ts |
| 52 | POST /api/finance/budgets | API/finance/adapter/in/web/FinanceBudgetController.java:50 | root | 사용자 | entities/finance/api/index.ts |
| 53 | PUT /api/finance/budgets/{id} | API/finance/adapter/in/web/FinanceBudgetController.java:68 | root | 사용자 | entities/finance/api/index.ts |
| 54 | PATCH /api/finance/budgets/{id}/share | API/finance/adapter/in/web/FinanceBudgetController.java:82 | root | 사용자 | entities/account/api/index.ts |
| 55 | PATCH /api/finance/budgets/{id}/unshare | API/finance/adapter/in/web/FinanceBudgetController.java:96 | root | 사용자 | entities/finance/api/index.ts |
| 56 | DELETE /api/finance/budgets/{id} | API/finance/adapter/in/web/FinanceBudgetController.java:109 | root | 사용자 | entities/finance/api/index.ts |
| 57 | POST /api/finance/bulk-register | API/finance/adapter/in/web/FinanceBulkController.java:35 | root | 사용자 | entities/finance/api/index.ts |
| 58 | GET /api/finance/categories | API/finance/adapter/in/web/FinanceCategoryController.java:34 | root | 사용자 | entities/finance/api/index.ts |
| 59 | POST /api/finance/categories | API/finance/adapter/in/web/FinanceCategoryController.java:63 | root | 사용자 | entities/finance/api/index.ts |
| 60 | PUT /api/finance/categories/{id} | API/finance/adapter/in/web/FinanceCategoryController.java:85 | root | 사용자 | entities/finance/api/index.ts |
| 61 | PATCH /api/finance/categories/{id}/share | API/finance/adapter/in/web/FinanceCategoryController.java:99 | root | 사용자 | entities/account/api/index.ts |
| 62 | PATCH /api/finance/categories/{id}/unshare | API/finance/adapter/in/web/FinanceCategoryController.java:112 | root | 사용자 | entities/finance/api/index.ts |
| 63 | DELETE /api/finance/categories/{id} | API/finance/adapter/in/web/FinanceCategoryController.java:125 | root | 사용자 | entities/finance/api/index.ts |
| 64 | GET /api/finance/groups | API/finance/adapter/in/web/FinanceGroupController.java:38 | root | 사용자 | entities/finance/api/index.ts |
| 65 | GET /api/finance/groups/{id}/members | API/finance/adapter/in/web/FinanceGroupController.java:50 | root | 사용자 | entities/finance/api/index.ts |
| 66 | POST /api/finance/groups/{id}/invitations | API/finance/adapter/in/web/FinanceGroupController.java:65 | root | 사용자 | entities/finance/api/index.ts |
| 67 | PATCH /api/finance/invitations/{code} | API/finance/adapter/in/web/FinanceGroupController.java:81 | root | 사용자 | entities/finance/api/index.ts |
| 68 | DELETE /api/finance/groups/{id}/members/{userId} | API/finance/adapter/in/web/FinanceGroupController.java:99 | root | 사용자 | entities/finance/api/index.ts |
| 69 | GET /api/finance/transactions | API/finance/adapter/in/web/FinanceTransactionController.java:34 | root | 사용자 | entities/finance/api/index.ts |
| 70 | POST /api/finance/transactions | API/finance/adapter/in/web/FinanceTransactionController.java:51 | root | 사용자 | entities/finance/api/index.ts |
| 71 | PUT /api/finance/transactions/{id} | API/finance/adapter/in/web/FinanceTransactionController.java:67 | root | 사용자 | entities/finance/api/index.ts |
| 72 | PATCH /api/finance/transactions/{id}/share | API/finance/adapter/in/web/FinanceTransactionController.java:81 | root | 사용자 | entities/account/api/index.ts |
| 73 | PATCH /api/finance/transactions/{id}/unshare | API/finance/adapter/in/web/FinanceTransactionController.java:94 | root | 사용자 | entities/finance/api/index.ts |
| 74 | DELETE /api/finance/transactions/{id} | API/finance/adapter/in/web/FinanceTransactionController.java:107 | root | 사용자 | entities/finance/api/index.ts |
| 75 | GET /api/finance/monthly-closings | API/finance/adapter/in/web/MonthlyClosingController.java:28 | root | 사용자 | entities/finance/api/index.ts |
| 76 | PATCH /api/finance/monthly-closings/{month} | API/finance/adapter/in/web/MonthlyClosingController.java:41 | root | 사용자 | entities/finance/api/index.ts |
| 77 | GET /api/market/fear-greed | API/market/adapter/in/web/FearGreedController.java:33 | root | 공개 | entities/market/api/index.ts |
| 78 | GET /api/market/holidays | API/market/adapter/in/web/MarketHolidayController.java:36 | root | 공개 | entities/market/api/index.ts |
| 79 | GET /api/market/session | API/market/adapter/in/web/MarketHolidayController.java:56 | root | 공개 | entities/market/api/index.ts |
| 80 | GET /api/market/candles | API/market/adapter/in/web/MarketHolidayController.java:65 | root | 공개 | entities/market/api/index.ts |
| 81 | POST /api/fcm/tokens | API/notify/adapter/in/web/FcmController.java:27 | root | 사용자 | entities/fcm/api/index.ts |
| 82 | DELETE /api/fcm/tokens/{token} | API/notify/adapter/in/web/FcmController.java:37 | root | 사용자 | entities/fcm/api/index.ts |
| 83 | GET /api/auth/status-stream | API/notify/adapter/in/web/StatusStreamController.java:29 | root | 사용자 | app/api/auth/status-stream/route.ts |
| 84 | GET /api/trades/stream | API/notify/adapter/in/web/TradeStreamController.java:26 | root | 사용자 | app/api/trades/stream/route.ts |
| 85 | POST /api/auth/kakao/callback | API/user/adapter/in/web/AuthController.java:49 | root | 공개 | app/auth/callback/route.ts |
| 86 | POST /api/auth/refresh | API/user/adapter/in/web/AuthController.java:68 | root | 공개 | shared/lib/api-client/index.ts |
| 87 | POST /api/auth/logout | API/user/adapter/in/web/AuthController.java:85 | root | 공개 | shared/lib/api-client/index.ts |
| 88 | GET /api/auth/me | API/user/adapter/in/web/AuthController.java:112 | root | 사용자 | entities/user/api/index.ts |
| 89 | POST /api/auth/approval-requests | API/user/adapter/in/web/AuthController.java:126 | root | 사용자 | app/api/auth/reapply-done/route.ts |
| 90 | DELETE /api/auth/me | API/user/adapter/in/web/AuthController.java:135 | root | 사용자 | entities/user/api/index.ts |
| 91 | POST /api/auth/dev-approve/{userId} | API/user/adapter/in/web/DevAuthController.java:55 | root | 공개(local 프로파일 전용) | 호출 0 |
| 92 | POST /api/auth/dev-token | API/user/adapter/in/web/DevAuthController.java:65 | root | 공개(local 프로파일 전용) | 호출 0 |
| 93 | POST /api/auth/dev-e2e-token | API/user/adapter/in/web/DevAuthController.java:73 | root | 공개(local 프로파일 전용) | 호출 0 |
| 94 | POST /api/auth/dev-admin-token | API/user/adapter/in/web/DevAuthController.java:80 | root | 공개(local 프로파일 전용) | 호출 0 |
| 95 | PUT /api/settings/telegram | API/user/adapter/in/web/SettingsController.java:38 | root | 사용자 | entities/user/api/index.ts |
| 96 | DELETE /api/settings/telegram | API/user/adapter/in/web/SettingsController.java:48 | root | 사용자 | entities/user/api/index.ts |
| 97 | PATCH /api/settings/notification-channel | API/user/adapter/in/web/SettingsController.java:57 | root | 사용자 | entities/user/api/index.ts |
| 98 | PATCH /api/settings/notifications/{type} | API/user/adapter/in/web/SettingsController.java:71 | root | 사용자 | entities/user/api/index.ts |
| 99 | PUT /api/settings/strategy-suggestions | API/user/adapter/in/web/SettingsController.java:89 | root | 사용자 | entities/user/api/index.ts |
| 100 | PATCH /api/settings/nickname | API/user/adapter/in/web/SettingsController.java:100 | root | 사용자 | entities/user/api/index.ts |
| 101 | POST /api/admin/scheduler/kbland-housing-benchmark | API/web/AdminSchedulerController.java:55 | root | ADMIN | 호출 0 |
| 102 | POST /api/admin/scheduler/kbland-price-index | API/web/AdminSchedulerController.java:63 | root | ADMIN | 호출 0 |
| 103 | POST /api/admin/scheduler/kbland-price-index/full-refresh | API/web/AdminSchedulerController.java:71 | root | ADMIN | 호출 0 |
| 104 | POST /api/admin/scheduler/market-index-prices | API/web/AdminSchedulerController.java:79 | root | ADMIN | 호출 0 |
| 105 | GET /api/meta | API/web/MetaController.java:50 | root | 공개 | entities/meta/api/index.ts |
| 106 | GET /api/accounts | TC/account/adapter/in/web/AccountController.java:33 | trading-core | 사용자 | entities/account/api/index.ts |
| 107 | POST /api/accounts | TC/account/adapter/in/web/AccountController.java:47 | trading-core | 사용자 | entities/account/api/index.ts |
| 108 | PUT /api/accounts/{id} | TC/account/adapter/in/web/AccountController.java:62 | trading-core | 사용자 | entities/account/api/index.ts |
| 109 | PUT /api/accounts/{id}/credentials | TC/account/adapter/in/web/AccountController.java:83 | trading-core | 사용자 | entities/account/api/index.ts |
| 110 | DELETE /api/accounts/{id} | TC/account/adapter/in/web/AccountController.java:100 | trading-core | 사용자 | entities/account/api/index.ts |
| 111 | POST /api/accounts/connection-tests | TC/account/adapter/in/web/AccountController.java:115 | trading-core | 사용자 | entities/account/api/index.ts |
| 112 | GET /api/internal/accounts | TC/account/adapter/in/web/AccountInternalController.java:36 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 113 | GET /api/internal/accounts/count | TC/account/adapter/in/web/AccountInternalController.java:55 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 114 | GET /api/internal/broker/candles/latest | TC/broker/adapter/in/web/CandleInternalController.java:26 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 115 | GET /api/internal/broker/candles/range | TC/broker/adapter/in/web/CandleInternalController.java:34 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 116 | GET /api/internal/marketcalendar/holidays | TC/marketcalendar/adapter/in/web/MarketCalendarInternalController.java:31 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 117 | GET /api/internal/marketcalendar/is-open | TC/marketcalendar/adapter/in/web/MarketCalendarInternalController.java:36 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 118 | GET /api/internal/marketcalendar/session | TC/marketcalendar/adapter/in/web/MarketCalendarInternalController.java:41 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 119 | POST /api/internal/fida-orders | TC/privacy/adapter/in/web/FidaOrderController.java:37 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 120 | GET /api/internal/privacy/trade-bases/{baseId} | TC/privacy/adapter/in/web/PrivacyBaseInternalController.java:28 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 121 | PATCH /api/internal/privacy/trade-bases/{baseId} | TC/privacy/adapter/in/web/PrivacyBaseInternalController.java:34 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 122 | PATCH /api/internal/privacy/trade-bases/{baseId}/orders/{orderId} | TC/privacy/adapter/in/web/PrivacyBaseInternalController.java:40 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 123 | POST /api/internal/privacy/trade-bases/{baseId}/orders | TC/privacy/adapter/in/web/PrivacyBaseInternalController.java:48 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 124 | DELETE /api/internal/privacy/trade-bases/{baseId}/orders/{orderId} | TC/privacy/adapter/in/web/PrivacyBaseInternalController.java:54 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 125 | GET /api/internal/privacy/trade-bases | TC/privacy/adapter/in/web/PrivacyInternalQueryController.java:24 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 126 | GET /api/backtest | TC/stats/adapter/in/web/BacktestController.java:37 | trading-core | 사용자 | entities/backtest/api/index.ts |
| 127 | GET /api/crypto-market-indicators | TC/stats/adapter/in/web/CryptoMarketIndicatorController.java:30 | trading-core | 공개 | entities/market/api/index.ts |
| 128 | GET /api/accounts/{accountId}/crypto-portfolio | TC/stats/adapter/in/web/CryptoPortfolioController.java:38 | trading-core | 사용자 | entities/trade/api/index.ts |
| 129 | GET /api/accounts/{accountId}/crypto-prices | TC/stats/adapter/in/web/CryptoPortfolioController.java:54 | trading-core | 사용자 | entities/trade/api/index.ts |
| 130 | GET /api/accounts/{accountId}/cycle-history | TC/stats/adapter/in/web/DashboardController.java:35 | trading-core | 사용자 | entities/trade/api/index.ts |
| 131 | GET /api/daily-trades | TC/stats/adapter/in/web/DashboardController.java:53 | trading-core | 사용자 | entities/trade/api/index.ts |
| 132 | GET /api/internal/trading/stats/portfolio/current | TC/stats/adapter/in/web/PortfolioQueryInternalController.java:26 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 133 | GET /api/internal/trading/stats/portfolio/history | TC/stats/adapter/in/web/PortfolioQueryInternalController.java:34 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 134 | GET /api/accounts/{accountId}/portfolio | TC/stats/adapter/in/web/StatisticsController.java:38 | trading-core | 사용자 | entities/trade/api/index.ts |
| 135 | GET /api/accounts/{accountId}/daily-trades | TC/stats/adapter/in/web/StatisticsController.java:53 | trading-core | 사용자 | entities/trade/api/index.ts |
| 136 | GET /api/accounts/{accountId}/prices | TC/stats/adapter/in/web/StatisticsController.java:77 | trading-core | 사용자 | entities/account/api/index.ts |
| 137 | GET /api/stats/summary | TC/stats/adapter/in/web/TradingStatsController.java:33 | trading-core | 사용자 | entities/stats/api/index.ts |
| 138 | GET /api/stats/equity-curve | TC/stats/adapter/in/web/TradingStatsController.java:40 | trading-core | 사용자 | entities/stats/api/index.ts |
| 139 | GET /api/stats/cycles | TC/stats/adapter/in/web/TradingStatsController.java:52 | trading-core | 사용자 | entities/stats/api/index.ts |
| 140 | GET /api/internal/trading/stats/investment-points | TC/stats/adapter/in/web/TradingStatsInternalController.java:28 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 141 | GET /api/internal/trading/stats/exchange-rate | TC/stats/adapter/in/web/TradingStatsInternalController.java:41 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 142 | GET /api/accounts/{accountId}/ledger-totals | TC/trading/adapter/in/web/AccountLedgerTotalsController.java:30 | trading-core | 사용자 | entities/account/api/index.ts |
| 143 | DELETE /api/orders/{orderId} | TC/trading/adapter/in/web/OrderCancelController.java:25 | trading-core | 사용자 | entities/order/api/index.ts |
| 144 | PATCH /api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status | TC/trading/adapter/in/web/StrategyStatusInternalController.java:29 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 145 | GET /api/trading-cycles | TC/trading/adapter/in/web/TradingCycleController.java:51 | trading-core | 사용자 | entities/order/api/index.ts |
| 146 | GET /api/accounts/{accountId}/trading-cycles | TC/trading/adapter/in/web/TradingCycleController.java:61 | trading-core | 사용자 | entities/order/api/index.ts |
| 147 | POST /api/accounts/{accountId}/trading-cycles | TC/trading/adapter/in/web/TradingCycleController.java:72 | trading-core | 사용자 | entities/order/api/index.ts |
| 148 | GET /api/trading-cycles/{id} | TC/trading/adapter/in/web/TradingCycleController.java:86 | trading-core | 사용자 | entities/order/api/index.ts |
| 149 | PUT /api/trading-cycles/{id} | TC/trading/adapter/in/web/TradingCycleController.java:95 | trading-core | 사용자 | entities/order/api/index.ts |
| 150 | PUT /api/trading-cycles/{id}/vr-config | TC/trading/adapter/in/web/TradingCycleController.java:107 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 151 | POST /api/trading-cycles/{id}/capital-injection | TC/trading/adapter/in/web/TradingCycleController.java:118 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 152 | POST /api/trading-cycles/{id}/capital-withdrawal | TC/trading/adapter/in/web/TradingCycleController.java:129 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 153 | PUT /api/trading-cycles/{id}/dca-config | TC/trading/adapter/in/web/TradingCycleController.java:140 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 154 | DELETE /api/trading-cycles/{id} | TC/trading/adapter/in/web/TradingCycleController.java:151 | trading-core | 사용자 | entities/order/api/index.ts |
| 155 | PATCH /api/trading-cycles/{id}/pause | TC/trading/adapter/in/web/TradingCycleController.java:161 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 156 | PATCH /api/trading-cycles/{id}/archive | TC/trading/adapter/in/web/TradingCycleController.java:171 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 157 | PATCH /api/trading-cycles/{id}/resume | TC/trading/adapter/in/web/TradingCycleController.java:181 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 158 | POST /api/trading-cycles/{id}/execute | TC/trading/adapter/in/web/TradingCycleController.java:191 | trading-core | 사용자 | entities/order/api/index.ts |
| 159 | DELETE /api/trading-cycles/{id}/execute | TC/trading/adapter/in/web/TradingCycleController.java:200 | trading-core | 사용자 | entities/order/api/index.ts |
| 160 | GET /api/trading-cycles/{id}/preview | TC/trading/adapter/in/web/TradingCycleController.java:209 | trading-core | 사용자 | entities/order/api/index.ts |
| 161 | GET /api/accounts/{accountId}/trading-cycles/previews | TC/trading/adapter/in/web/TradingCycleController.java:218 | trading-core | 사용자 | entities/order/api/index.ts |
| 162 | GET /api/accounts/{accountId}/strategy-seed-preview | TC/trading/adapter/in/web/TradingCycleController.java:228 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 163 | GET /api/trading-cycles/{id}/history | TC/trading/adapter/in/web/TradingCycleController.java:241 | trading-core | 사용자 | entities/trade/api/index.ts |
| 164 | GET /api/trading-cycles/{id}/orders | TC/trading/adapter/in/web/TradingCycleController.java:257 | trading-core | 사용자 | entities/order/api/index.ts |
| 165 | GET /api/trading-cycles/{id}/dca-executions | TC/trading/adapter/in/web/TradingCycleController.java:272 | trading-core | 사용자 | entities/strategy/api/index.ts |
| 166 | POST /api/internal/trading/trade-corrections | TC/trading/adapter/in/web/TradingInternalCommandController.java:22 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 167 | GET /api/internal/trading/orders | TC/trading/adapter/in/web/TradingInternalQueryController.java:34 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 168 | GET /api/internal/trading/orders/distinct-account-ids | TC/trading/adapter/in/web/TradingInternalQueryController.java:42 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 169 | GET /api/internal/trading/accounts/{accountId}/strategies | TC/trading/adapter/in/web/TradingInternalQueryController.java:50 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 170 | POST /api/internal/trading/strategies/by-account-ids | TC/trading/adapter/in/web/TradingInternalQueryController.java:56 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 171 | POST /api/internal/trading/strategy-summaries | TC/trading/adapter/in/web/TradingInternalQueryController.java:64 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 172 | GET /api/internal/trading/accounts/{accountId}/strategies/{strategyId}/trade-dates | TC/trading/adapter/in/web/TradingInternalQueryController.java:71 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 173 | GET /api/internal/trading/policy-settings | TC/trading/adapter/in/web/TradingPolicyInternalController.java:26 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 174 | PUT /api/internal/trading/policy-settings | TC/trading/adapter/in/web/TradingPolicyInternalController.java:32 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 175 | POST /api/internal/trading/scheduler/open | TC/trading/adapter/in/web/TradingSchedulerInternalController.java:47 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |
| 176 | POST /api/internal/trading/scheduler/close | TC/trading/adapter/in/web/TradingSchedulerInternalController.java:54 | trading-core | 내부(X-Internal-Token) | 해당 없음(서버 간) |


ui 호출 0 (내부 API 30개 제외) 14개: `POST /telegram/webhook`(텔레그램이 호출), `GET /api/admin/_ping`, `GET /api/admin/accounts/{accountId}/strategies/{strategyId}/trade-dates`, `POST /api/admin/trades/manual-fills`, `POST /api/admin/scheduler/{open,close,kbland-housing-benchmark,kbland-price-index,kbland-price-index/full-refresh,market-index-prices}`(6개, 수동 트리거), `POST /api/auth/dev-*` 4개(local 전용). 전부 ui 코드 어디에도 문자열이 없다.
권한별: 공개 12, 공개(local 전용) 4, 사용자 96, ADMIN 34, 내부 30. 프로세스별: root 105, trading-core 71.
외부 공인 경로: `POST /api/internal/fida-orders`만 Caddy가 공인 도메인으로 trading에 넘긴다(`deploy/server/caddy/kista-api.caddy` `@trading` regex).

숫자 요약: 엔드포인트 176(root 105 / trading-core 71), ui 호출 0은 14(서버 간 내부 30개 별도).
접근 로그가 어디에도 없어 엔드포인트별 7일 사용량은 측정 불가(미확인: access log·Prometheus 없음).

## B. 스케줄러 전수

방법: `grep @Scheduled|TaskScheduler`(main 3개 서브프로젝트) + 운영 `public.scheduler_locks`·`trading.scheduler_locks`(이름당 마지막 1회만 남는다. `locked_at`=마지막 실행 시작, 표는 KST로 환산). 락을 안 쓰는 fixedDelay 작업은 7일 흔적이 없다(컨테이너 로그는 가동 4시간치뿐이라 건수 셀 수 없음 — "흔적 없음"). role: `scheduler.enabled=false`가 kista-api, 나머지 true(`application.yml:76`, `application-prod.yml:20`)이며 trading-core 스케줄러는 kista-trading 컨테이너에서만 돈다(root 쪽은 `@ConditionalOnProperty(scheduler.enabled)`로 kista-scheduler).

| # | 클래스 file:line | cron/주기 (KST) | 하는 일 | role(프로세스) | 락 이름 | 운영 마지막 실행(KST) |
|---|---|---|---|---|---|---|
| 1 | `API/benchmark/adapter/in/schedule/MarketIndexPriceSyncScheduler.java:26` | 매일 09:00 | 벤치마크 ETF 종가 동기화(Alpaca) | kista-scheduler(root) | market-index-price-sync | 2026-11-12 09:00 |
| 2 | `.../KbLandPriceIndexScheduler.java:29` | 토 08:10 | KB Land 주간 매매가격지수 수집(최근 구간) | kista-scheduler | kbland-price-index | 2026-11-07 08:10 |
| 3 | `.../KbLandPriceIndexScheduler.java:34` | 매월 1일 08:20 | KB Land 20년 전체 재수집 | kista-scheduler | kbland-price-index-full | 2026-11-01 08:20 |
| 4 | `.../KbLandHousingBenchmarkScheduler.java:24` | 토 08:00 | KB Land 주택 벤치마크 수집(클래스 주석 `:14`은 "일요일 07:00"이라 실제 cron과 불일치) | kista-scheduler | kbland-housing-benchmark | 2026-11-07 08:00 |
| 5 | `API/user/adapter/in/schedule/RefreshTokenCleanupScheduler.java:25` | 매일 04:00 | 만료 refresh token 삭제 | kista-scheduler | refresh-token-expired-cleanup | 2026-11-12 04:00 |
| 6 | `.../RefreshTokenCleanupScheduler.java:35` | 매일 03:05 | grace 지난 회전 RT 삭제 | kista-scheduler | refresh-token-rotated-cleanup | 2026-11-12 03:05 |
| 7 | `API/finance/adapter/in/schedule/FinanceRegistrationReminderScheduler.java:25` | 매월 말일 21:00 | 이번 달 가계부 등록 없는 사용자에게 알림 | kista-scheduler | finance-registration-reminder | 2026-10-31 21:00 |
| 8 | `API/market/adapter/in/schedule/FearGreedScheduler.java:27` | 매일 07:00·12:00 | CNN 공포탐욕지수 수집 | kista-scheduler | fear-greed | 2026-11-12 12:00 |
| 9 | `API/web/UserEventResubmitScheduler.java:37` | fixedDelay 10분(초기 10분) | 실패한 사용자 동기화 이벤트(root EPR) 재발행, 첫 실패 시 관리자 알림 | kista-scheduler | 없음 | 흔적 없음 |
| 10 | `API/notify/adapter/in/redis/PushNotificationStreamConsumer.java:54` | fixedDelay 5분 | FCM 푸시 Redis 스트림 pending 복구(XCLAIM) | root(조건 없음 — kista-api·kista-scheduler 모두) | 없음 | 흔적 없음 |
| 11 | `API/admin/adapter/in/redis/AppErrorStreamConsumer.java:55` | fixedDelay 5분 | 오류 스트림 pending 복구 | root(조건 없음) | 없음 | 흔적 없음 |
| 12 | `TC/marketcalendar/adapter/in/schedule/MarketCalendarRefreshScheduler.java:53` | 매년 1/1 00:00 | 미국 휴장일 향후 3년 적재 | kista-trading | market-calendar-yearly | trading 락 행 없음(올해 1/1 행은 지워졌거나 미기록) |
| 13 | `.../MarketCalendarRefreshScheduler.java:64` | 매월 1일 01:00 | 해당 월 휴장일 최신화 | kista-trading | market-calendar-monthly | 2026-11-01 01:00 |
| 14 | `.../MarketCalendarRefreshScheduler.java:40`(기동 시 실행) | 앱 기동마다 | 누락 연도 캘린더 적재 | kista-trading | market-calendar-startup | 2026-11-12 20:14(마지막 기동) |
| 15 | `TC/account/adapter/in/schedule/ApiKeyExpiryScheduler.java:32` | 매일 10:00 | API 키 만료 30·7·0일 전 알림 이벤트 발행 | kista-trading | api-key-expiry | 2026-11-12 10:00 |
| 16 | `TC/trading/adapter/in/schedule/TradingOpenScheduler.java:46` | 월~금 22:00 | 개장 배치: 주문 생성·INFINITE 매도 선접수·예수금 부족 알림 | kista-trading | trading-open | 2026-11-12 22:00(종료 23:30) |
| 17 | `TC/trading/adapter/in/schedule/TradingCloseScheduler.java:70` | 매시 :05 | 오늘·내일 마감 접수 예약 보정 | kista-trading | (예약은 `taskScheduler.schedule` `:111`로 거래일별 등록) | 흔적 없음 |
| 18 | (예약) `TradingCloseScheduler.java:111` | 거래일 마감 시각 | 마감 배치 실행(TaskScheduler 동적 등록) | kista-trading | trading-close | 2026-11-12 05:30(종료 06:10) |
| 19 | `TradingCloseScheduler.java:79` | 화~토 04:30 | 휴장·조기 마감일 healthchecks.io 하트비트 대신 핑 | kista-trading | 없음 | 흔적 없음 |
| 20 | `TC/trading/adapter/in/schedule/CryptoDcaScheduler.java:27` | 매 10분 | 코인 DCA 틱 + 하트비트 핑 | kista-trading | crypto-dca | 2026-11-13 00:10 |
| 21 | `TC/trading/adapter/in/redis/UserEventStreamConsumer.java:63` | fixedDelay 5분 | 사용자 이벤트 스트림 pending 복구 | kista-trading | 없음 | 흔적 없음 |
| 22 | `TC/trading/adapter/in/schedule/UserEventResubmitScheduler.java:36` | fixedDelay 10분 | trading EPR 실패 이벤트 재발행 | kista-trading | 없음 | 흔적 없음 |
| 23 | `TC/trading/adapter/in/schedule/TradingBatchResumer.java:30`(`@Scheduled` 아님, 기동 이벤트) | 앱 기동마다 | 미완료 매매 배치 체크포인트부터 재개(`@Profile("!local")`) | kista-trading | 잔여 락 인수 | 마지막 기동 2026-11-12 20:14 |

`@Scheduled` 선언은 20개(Redis 복구 3개 포함, 같은 클래스 중복 포함), 동적 `taskScheduler.schedule` 1곳, 기동 시 재개 1곳(`TradingBatchResumer`)과 기동 시 적재 1곳. 수동 트리거 엔드포인트가 있는 것: #1~#4·open·close(A절의 `/api/admin/scheduler/*` 6개).
`SH/platform/redis/RedisStreamSubscriber.java`는 `@Scheduled`를 직접 갖지 않는다(서브클래스가 배선, `:21-22` 주석).

운영 락 테이블의 죽은 행(코드에 더는 없는 이름): `public.fear-greed-{12hourly,6hourly,daily}`(마지막 2026-06~07), `public.market-calendar-{monthly,startup}`·`public.trading-{open,close}`(2026-09 이전 단일 프로세스 시절), `trading.ledger-reconciliation`(마지막 2026-10-30 09:00, 코드에 이 이름 없음 — `grep ledger-reconciliation` 0건).

숫자 요약: 스케줄 진입점 23개(root 11 / trading-core 12). 락 이름 기준 마지막 실행이 7일 안인 것 11개(#1·2·4·5·6·8·14·15·16·18·20), 7일 밖이지만 월 주기라 정상인 것 3개(#3·7·13), 락 행은 있으나 코드에 없는 죽은 행 7개.
미확인: 락 테이블은 이름당 마지막 1회만 보존해 7일 실행 횟수는 못 센다.

## C. 알림 전수

방법: `NotificationType`(`SH/kernel/NotificationType.java:5` — TRADING_ALERT, MARKET_ALERT, FINANCE_REMINDER 3종, 사용자 설정 게이트), `NotifyPort`·`TradingNotifyPort`(관리자 봇), `UserNotificationPort`·`TradingUserNotificationPort`(사용자 봇+FCM), SSE 레지스트리를 따라가 문구 생성 지점을 찾았다.
경로: trading-core는 사용자 텔레그램을 **직접 발송**하고 FCM만 Redis Stream(`user-push`)으로 root에 위임한다(`TC/notify/adapter/out/gateway/TradingUserNotificationAdapter.java:163-177`). root는 `UserChannelNotifier`(`API/notify/adapter/out/gateway/UserChannelNotifier.java:30`)가 수신자 조회→유형별 설정 게이트→텔레그램·FCM 발송을 한다.
발송 이력 테이블: **없음**(전 스키마 테이블 목록에 알림 로그 없음). 그래서 종류별 30일 건수는 트리거가 되는 원본 테이블 행으로 대신한다(아래 "30일 근거" 열).

### C-1. 사용자 알림 (사용자 봇 텔레그램 + FCM)

| 종류(이벤트 → 메서드) | 문구 생성 `file:line` | 트리거 | 수신자·유형 게이트 | 30일 근거(운영) |
|---|---|---|---|---|
| 매매 결산 (`TradingReportReadyEvent` → `notifyTradingReport`) | `TC/notify/.../TradingUserNotificationAdapter.java:32` (리스너 `TradingReportNotifier.java:35`) | 개장·마감 배치가 전략별 리포트 저장 | 전략 소유 사용자, TRADING_ALERT | `trading_batch_report` 124행 |
| 사이클 종료 (`CycleCompletedEvent`·`CycleEndedEvent`) | `:43` (리스너 `CycleLifecycleNotifier.java:24`, `CycleEndedNotifier.java:24`) | 사이클 종료 | 소유자, TRADING_ALERT | 사이클 종료 4건 |
| 새 사이클 시작 (`NewCycleStartedEvent`) | `:54` (`CycleLifecycleNotifier.java:31`) | 사이클 회전 | 소유자 | 사이클 시작 26건 |
| 전략 자동 일시정지 (`StrategyAutoPausedEvent`) | `:65` (`TradingAlertNotifier.java:84`) | 소액 시드로 새 사이클 불가 | 소유자 | 측정 불가(이력 없음) |
| 사이클 회전 실패 (`CycleRotationFailedEvent`) | `:76` (`TradingAlertNotifier.java:90`) | 다음 사이클 여는 중 오류 | 소유자 | 측정 불가 |
| 잔고·수량·원장 부족 (`InsufficientBalanceEvent`) | `:85-100` (`TradingAlertNotifier.java:47`) | 주문 거절 또는 원장 상한 | 소유자 + 관리자 | 측정 불가 |
| 매매 오류 (`TradingErrorEvent`) | `:104` (`TradingAlertNotifier.java:37`) | 매매 코드 53곳에서 발행 | userId 있으면 사용자, 없으면 관리자 | `app_error_logs` 30일 99행(전 종류) |
| 매매 일시 중단 (`BatchInterruptedEvent`) | `:109` (`TradingAlertNotifier.java:78`) | 재배포로 배치 중단 | 소유자 | 측정 불가 |
| 장 개시·마감 (`MarketOpenEvent`·`MarketCloseEvent`) | `:118`,`:123` (`TradingAlertNotifier.java:62,67`, 발행 `MarketEventNotifier`) | 개장·마감 배치 | 활성 전략 사용자, MARKET_ALERT | 배치 실행 30일×사용자 |
| DCA 미주문 (`DcaRoundSkippedEvent`) | `:128` (`TradingAlertNotifier.java:72`) | DCA 회차 조건 불충족·잔고 부족 | 소유자 | `dca_execution` SKIPPED 21행 |
| 코인 체결 (`CryptoOrderFilledEvent`) | `:141` (`CryptoTradeNotifier.java:27`) | 코인 주문 체결 동기화 | 소유자, TRADING_ALERT | 바이낸스 DCA 주문 65건(30일) |
| API 키 만료 (`ApiKeyExpiringEvent`) | `:150` (`ApiKeyExpiryNotifier.java:18`) | 일일 점검 30·7·0일 전 | 소유자 | `api_key_expires_at` 설정 계좌 6개 |
| 신규 가입 신청(관리자 대상, 승인·거절 버튼) | `API/notify/adapter/out/gateway/TelegramUserNotificationAdapter.java:23`(`CompositeUserNotificationAdapter.java:38`) | 가입·재신청 | 관리자 봇 | 가입 0건(사용자 2명, 가입일 2026-06-15·18) |
| 신규 가입(자동 승인) | `TelegramUserNotificationAdapter.java:34` (`Composite...:36`) | 자동 승인 설정 가입 | 관리자 | 0건 |
| 가입 승인 / 거절 | `TelegramUserNotificationAdapter.java:40,45` (`Composite...:44,49`) | 관리자 승인·거절 | 신청 사용자 | 0건 (`audit_logs` USER_APPROVE 1건, 2026-06-17) |
| 가계부 등록 리마인더 (`FinanceRegistrationReminderDueEvent`) | `API/finance/application/service/FinanceRegistrationReminderNotifier.java:34,49` (`FinanceReminderChannelNotifier.java:19`) | 매월 말 21:00 스케줄러 | 이달 등록 없는 사용자, FINANCE_REMINDER | 마지막 실행 2026-10-31 |

### C-2. 관리자 알림 (관리자 봇 텔레그램, `TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID`)

| 종류 | 문구 `file:line` | 트리거 |
|---|---|---|
| 스케줄러 시작·완료·실패 | `API/notify/adapter/out/gateway/SchedulerNotifier.java:19-24` | `SchedulerJobRunner`가 발행하는 `SchedulerLifecycleEvent`(root 스케줄러 전부) |
| 일반 오류 | `TC/notify/.../TradingNotifyAdapter.java:30` ("⚠️ 관리자 알림"), root `NotifyPort.notifyError` | `TradingErrorEvent`(userId 없음)·`OrderCancelFailedEvent`(`OrderCancelFailureNotifier.java:20`)·`PrivacyAlertRaisedEvent` BLOCKING(`PrivacyAlertNotifier.java:19`) 등. notifyError 호출 11곳, notifyInfo 4곳 |
| 잔고 부족 | `TradingNotifyAdapter.java:46` | `InsufficientBalanceEvent` |
| 휴장일 | `TradingNotifyAdapter.java:54` ("오늘은 휴장일입니다") | `MarketClosedEvent`(`TradingAlertNotifier.java:57`) |
| PRIVACY 경고 | `PrivacyAlertNotifier.java:24` | `PrivacyAlertRaisedEvent` WARNING |
| 공포탐욕 수집 실패 | `API/notify/.../AlertNotifier.java:21` | `FearGreedFetchFailedEvent` |
| 벤치마크 수집 알림 | `AlertNotifier.java:26` | `BenchmarkAlertRaisedEvent` |
| 사용자 cascade 삭제 완료 | `UserDeletedNotifier.java:19` | `UserDeletedEvent` |
| 사용자 이벤트 재발행 실패 | `API/web/UserEventResubmitScheduler.java:54`, trading 쪽 `TC/trading/adapter/in/schedule/UserEventResubmitScheduler.java` | 10분 주기 EPR 재발행 |
| 마감 예약 누락 등 매매 운영 알림 | `TradingCloseScheduler.java:128-134` 외 `TradingErrorEvent` 53곳 | 배치 |
| 관리자 승인 버튼 콜백 | `API/admin/adapter/in/telegram/TelegramBotService.java:59` | 텔레그램 웹훅 `POST /telegram/webhook` |

### C-3. SSE·FCM·기타 채널

| 종류 | 코드 | 트리거 | 수신자 |
|---|---|---|---|
| SSE `status` | `API/notify/adapter/out/sse/SseEmitterRegistry.java:36` (`GET /api/auth/status-stream`) | 사용자 상태 변경(승인·거절) | 해당 사용자(대기 화면) |
| SSE `trade` / `ping` | `TradeSseEmitterRegistry.java:63,39` (`GET /api/trades/stream`), 구독 `RedisTradeEventSubscriber.java` | 체결 건별(`RedisTradeEventPublisher`→Redis Pub/Sub) | 해당 사용자 |
| FCM 푸시 | `API/notify/adapter/out/gateway/FcmAdapter.java`, 소비 `PushNotificationStreamConsumer.java:50` | 위 사용자 알림 전부가 FCM 병행(채널 설정에 FCM 포함 시) | `fcm_device_tokens` 2행(WEB, 1명) |
| healthchecks.io 하트비트 | `TC/trading/adapter/out/heartbeat/HeartbeatAdapter`(74줄) | 개장·마감 완료·코인 틱 10분 | 외부 서비스(사람 아님) |

사용자 알림 채널 설정(운영): 사용자 2명 중 채널 FCM 1, ALL(텔레그램+FCM) 1. 텔레그램 봇 토큰 보유 1명. 유형 설정 행은 TRADING_ALERT=on, MARKET_ALERT=off 1명뿐(`user_notification_prefs` 2행).

숫자 요약: 사용자 알림 종류 17(텔레그램+FCM 병행), 관리자 알림 종류 11, SSE 2종·FCM·하트비트 각 1. 알림 유형 게이트 `NotificationType` 3종.
발송 이력 테이블이 없어 종류별 실제 발송 건수는 미확인(원본 행 수로만 근사). 사용자 알림 수신 대상은 운영에서 사실상 1명.

## D. 기능 목록 (기능 단위)

### D-0. 운영 전체 규모 (`ssh kista-server` → `kista-postgres` SELECT, 2026-11-13 조회)

한 줄: **사용자 2명(ACTIVE: ADMIN 1 + USER 1, 삭제 0, 가입 2026-06-15·18), 살아 있는 계좌 8개(전부 ADMIN 1명 소유 — USER 1명은 계좌 0), 활성 전략 8개(INFINITE 2·VR 2·PRIVACY 2·DCA 2, 그중 4개가 MOCK 계좌), 사실상 운영자 1명이 쓰는 시스템이다.** 가계부만 2명이 쓴다.

| 항목 | 값 |
|---|---|
| users | ACTIVE/ADMIN 1, ACTIVE/USER 1 (PENDING·REJECTED·삭제 0). `approvalRequired=false`(자동 승인, `admin_runtime_settings`) |
| accounts(삭제 제외) | BINANCE 1·KIS 1·MERITZ 3·MOCK 1(삭제된 MOCK 5개 별도)·TOSS 1·UPBIT 1 = 8, 전부 ADMIN 소유. 최근 30일 신규 5개 |
| 활성 전략 | 브로커×종류: BINANCE DCA 1, KIS INFINITE 1, MERITZ PRIVACY 1·VR 1, MOCK DCA·INFINITE·PRIVACY·VR 각 1 = 8개. TOSS·UPBIT 계좌는 전략 0 |
| 전략 누적 | 71개 중 63개 삭제됨, 최근 30일 생성 22개(운영자가 등록·삭제를 반복) |
| 계좌 정책(`trading-policy`) | KIS enabled=false, UPBIT enabled=false, MOCK·TOSS·MERITZ·BINANCE enabled=true. 전략 4종 모두 enabled=true |
| 사용자 알림 채널 | FCM 1·ALL 1, 텔레그램 봇 1명 연결, FCM 토큰 2행(WEB, 1명) |

### D-1. 기능별 표

줄 수 기준: 경로·파일명 정규식에 걸린 `.java`의 `wc -l` 합(main / test). 기능 사이에 겹치는 파일이 있다(예: 전략 공통과 INFINITE). 정규식은 아래 각주. 엔드포인트 수는 A절 분류. 테이블 수는 소유 스키마 기준 운영 테이블.
ui 화면은 `kista-ui/app` 라우트. "사용자 수"는 해당 테이블을 소유한 distinct 사용자(역할 기준, 신원 아님).

| # | 기능 | main / test 줄(파일) | 엔드포인트 | 스케줄러 | 테이블 | ui 화면 | 운영 흔적 (행 수·사용자·최근 쓰기·30일) | 외부 의존 |
|---|---|---|---|---|---|---|---|---|
| 1 | 가입·승인·인증(카카오 로그인, 재신청, 탈퇴) | 2663(77f) / 3101(28f) — `API/user/**` | 10(auth 6 + dev 4) | 2(RT 정리) | users, refresh_tokens, user_settings | `/login` `/pending` `/rejected` `/admin/pending` | users 2행, 가입 30일 0, refresh_tokens 6행(최근 2026-11-12 09:48) | 카카오 OAuth |
| 2 | 계좌 연결(등록·수정·키 교체·연결 테스트) | 1365(32f) / 2046(13f) — `TC/account/**`+컨트롤러 | 8 | 1(API 키 만료 점검) | accounts, broker_tokens | `/accounts` `/accounts/new` `/accounts/[id]/edit` | 계좌 8행(+삭제 5), 사용자 1, 최근 생성 2026-10-24, 30일 신규 5, 토큰 5행 | 각 증권사·거래소 API |
| 3 | 전략 등록·수정·일시정지·재개·삭제·미리보기(공통) | 3270(46f) / 8173(26f) — 전략 서비스·`domain/creation`·컨트롤러 | 23(전략 CRUD·상태·미리보기·이력·시드 미리보기 + 내부 조회) | 0 | strategy, strategy_version, strategy_cycle, cycle_position, orders | `/strategies` `/accounts/[id]/strategies/*` | strategy 71행(63 삭제), 30일 생성 22, strategy_version 82(30일 30), 사용자 1 | 시세(토스·증권사) |
| 4 | 무한매수(INFINITE) 자동매매 | 1004(16f) / 1029(4f) — `*Infinite*` | 전용 0(공통 사용) | 개장·마감 배치 공유 | strategy_infinite_version 22, cycle_position_infinite 283 | 전략 폼·상세 | 활성 2(KIS 1·MOCK 1), 주문 30일 309(KIS 162·MOCK 147), 최근 2026-11-12 23:30. TOSS INFINITE는 2026-08-04 이후 0. 사용자 1 | KIS(삭제 예정)·Mock |
| 5 | VR | 1643(25f) / 2882(12f) — `Vr*`·`vr/` | 1(`vr-config`) | 배치 공유 | strategy_vr_version 30, strategy_cycle_vr 42 | `/accounts/[id]/strategies/[sid]/reconfigure-vr` | 활성 2(MERITZ 1·MOCK 1), 주문 30일 627(메리츠 330·MOCK 297), 최근 11-12 23:30, VR 버전 30일 6. 사용자 1 | 메리츠 |
| 6 | PRIVACY(FIDA 기준 매매표 연동) | 2115(51f) / 1917(15f) — `privacy`·`Privacy*`·`Fida*` | 13(admin 6·internal 6·fida-orders 1) | 배치 공유(FIDA는 외부가 push) | trading_ref.privacy_trade_bases 377, privacy_trade_base_orders 1385 | `/admin/privacy-trades` | 활성 2(MERITZ 1·MOCK 1), 주문 30일 131(메리츠 56·MOCK 75), 기준표 최근 수신 2026-11-11 22:01, 감사 PRIVACY_BASE_UPDATE 90일 11. 사용자 1 | FIDA(별도 레포가 `POST /api/internal/fida-orders`로 push) |
| 7 | DCA(코인·주식) | 3033(62f) / 4216(25f) — `Dca*`·`Crypto(Dca|Order|Ledger|Failure|Trade)*` | 2(`dca-config`,`dca-executions`) | 1(코인 10분 틱) | strategy_dca_rule 31, strategy_dca_condition 19, dca_execution 84 | 전략 폼(DCA 조건) | 활성 2(BINANCE 1·MOCK 1), 주문 30일 65(바이낸스 55·MOCK 10), 실행 기록 ORDERED 64·SKIPPED 20(30일 전부), 최근 11-12 09:00. 사용자 1 | 바이낸스, 공포탐욕·RSI 지표 |
| 8 | 수동 매매(바로 주문·미리보기·주문 취소) | 1190(14f) / 2397(7f) | 3(+미리보기 2는 #3에 포함) | 0 | 전용 없음(orders 공유) | 전략 상세 | 전용 이력 테이블 없음 — 미확인(주문 행에 수동·배치 구분 없음) | 증권사 API |
| 9 | 수동 체결 보정 | 592(11f) / 754(3f) | 2(`manual-fills`,`trade-corrections`) | 0 | 전용 없음(orders·cycle_position 갱신) | 없음(ui 호출 0) | 감사 ORDER_CORRECTION 누적 2건, 최근 2026-07-02, 90일 0. 사용자 1 | 없음 |
| 10 | 투자금 입출금 | 616(15f) / 986(3f) | 2 | 0 | cycle_capital_event 5 | 전략 상세 폼 | INJECT 5건(전부 30일, 최근 11-10), WITHDRAW 0건. 사용자 1 | 없음 |
| 11 | 통계·수익 곡선·사이클 이력 | 4758(96f) / 6446(30f) — `stats` 모듈(백테스트·포트폴리오 포함) | 3(`summary`,`equity-curve`,`cycles`) | 0 | 전용 없음(원장 집계) | `/stats` | 전용 테이블 없음, 5분 TTL 메모리 캐시. 사용 흔적 미확인 | 없음 |
| 12 | 백테스트 | 1086(11f) / 2211(5f) | 1 | 0 | 없음 | `/stats/backtest` | 무상태 — 사용 흔적 미확인(`app_error_logs` 오류 0) | 일봉 조회(Alpaca·토스 캔들) |
| 13 | 포트폴리오·원장 합계·투자 지수 | 788(22f) / 996(8f) + 투자지수 264(8f) / 478(2f) | 포트폴리오·ledger 5 + 투자지수 internal 2 | 0 | 없음 | 계좌 상세·`/dashboard` | 무상태 — 미확인 | 증권사 잔고 API |
| 14 | 벤치마크(주택·ETF) | 2437(63f) / 2934(20f) — `API/benchmark/**` | 9(공개 5 + 수동 트리거 4) | 4(KB Land 3·ETF 종가 1) | kista_ref.housing_benchmark_prices 2236, housing_price_indices 22227, market_index_prices 21891 | `/stats/benchmark` | 최근 쓰기: ETF 종가 2026-11-12, 지수 11-06, 주택 10-30. 설정 `benchmarks.etf` 기본 QLD | KB Land, Alpaca(IEX) |
| 15 | 공포탐욕지수 | 938(37f) / 968(10f) — `API/market` | 1(공개) | 1 | kista_ref.fear_greed_snapshots 1640 | `/dashboard` | 최근 2026-11-12 12:00 | CNN, alternative.me |
| 16 | 코인 시장 지표·코인 포트폴리오 | 919(12f) / 1088(5f) | 3(지표 1 공개 + 포트폴리오·시세 2) | 0 | 없음 | `/dashboard` 등 | 무상태, 10분 TTL 캐시 | Binance·Coinbase·CoinMarketCap·mempool.space·Frankfurter·Upbit·Alpaca |
| 17 | 미국 시장 캘린더·시세 캔들 | 858(24f) / 786(7f) | 8(공개 3 + 내부 5) | 3(기동·연간·월간) | trading_ref.us_market_holidays 29, us_market_sessions 1096 | `/dashboard` | 월간 갱신 최근 2026-11-01. root→trading 호출 I/O 오류 90일 9건 | 외부 휴장일 소스 |
| 18 | 가계부(자산·수입·소비·저축·예산·그룹 공유) | 5024(112f) / 5441(27f) — `API/finance/**` | 42(사용자 38 + admin 카테고리 4) | 1(월말 리마인더) | finance 스키마 9개: transactions 804, asset_snapshots 1056, accounts 32, budgets 37, categories 75, groups 3 등 | `/finance/*` 9개 | 사용자 2명(거래 2·자산 1·그룹 멤버 2), 거래 최근 2026-11-11, 30일 신규 39(자산 44) | 없음 |
| 19 | 알림 설정·알림 게이트웨이(텔레그램·FCM·SSE) | 2422(65f) / 3361(43f) | 약 10(settings 6 + fcm 2 + SSE 2) | Redis 복구 3 | user_notification_prefs 2, fcm_device_tokens 2, user_notify_profile 2 | `/settings` | 사용자 1~2명 | 텔레그램 Bot API, FCM, Redis |
| 20 | 관리자 조회·승인·정정 | 3697(93f) / 4233(40f) — `API/admin/**` | 13+ | 수동 트리거 6 | audit_logs(7종 감사) | `/admin/{accounts,users,trades,pending,logs}` | audit USER_APPROVE 1, STRATEGY_PAUSE·RESUME 각 1, ORDER_CORRECTION 2 (모두 2026-07-02 이전) | 텔레그램 웹훅 |
| 21 | 관리자 런타임 설정(가입 승인·브로커·전략 정책) | 1171(28f) / 1583(15f) | 5(`/api/admin/settings` 2, `/api/runtime-config`, `policy-settings` 2) | 0 | admin_runtime_settings 1, trading_runtime_settings 1 | `/admin/settings` | 감사 RUNTIME_SETTINGS_UPDATE 누적 20, 90일 8, 최근 2026-10-26 | 없음 |
| 22 | 오류 로그·감사 로그 | 679(20f) / 732(10f) | 5 | 0(스트림 소비) | app_error_logs, audit_logs | `/admin/logs` | app_error_logs 30일 99행(UI 뷰포트 오류 72 포함) | 없음 |
| 23 | MOCK 브로커 | 339(4f) / 500(2f) + 분기 17파일 31줄 | 0(계좌 API 공유) | 0 | 없음(orders 등 공유) | 계좌 등록 | MOCK 계좌 1, 활성 전략 4(4종 전부), 30일 주문 529(전체 30일 주문 1,132건의 47%) | 없음 |
| 24 | 대시보드·일별 체결·사이클 이력 | 88(2f) / 159(2f) | 3 | 0 | 없음 | `/dashboard` | 무상태 | 없음 |
| 25 | 배치 실행 골격(개장·마감·재개·락·하트비트) | 3289(36f) / 7004(26f) | 내부 2 + admin 2 | 6 | trading_batch_run 59, trading_batch_report 159, scheduler_locks | 없음 | 개장 22:00·마감 05:30 매일 실행(trading-open 마지막 거래일 2026-11-13) | healthchecks.io |
| 26 | 브로커 어댑터: KIS / TOSS / MERITZ / BINANCE / UPBIT | 1764 / 2160 / 1113 / 729 / 657 (main) | — | — | broker_tokens | — | 활성 전략: KIS INFINITE 1·MERITZ VR·PRIVACY 각 1·BINANCE DCA 1·TOSS 0·UPBIT 0 | 각 벤더 |
| 27 | 텔레그램 봇 웹훅(관리자 승인 버튼) | `API/admin/adapter/in/telegram` | 1 | 0 | — | 없음 | 미확인 | 텔레그램 |

정규식(경로 기준): 계좌 `tradingcore/account/|AccountController|AccountInternal|AdminAccount|AccountCascade`, VR `Vr`, PRIVACY `privacy|Privacy|Fida`, DCA `Dca|Crypto(Dca|Order|Ledger|Failure|Trade)`, 수동 보정 `ManualTradeCorrection|TradeCorrection|ManualFill|AdminTradeController`, 투자금 `Capital|CycleCapital`, MOCK `/mock/|Mock[A-Z]`, 나머지는 모듈 디렉토리 전체(`API/user`, `API/benchmark`, `API/market`, `API/finance`, `API/admin`, `TC/stats`).
전체 합계(참고): main 56,003줄(root 16,495 + trading-core 36,075 + shared 3,433), test 77,144줄(21,097 + 54,104 + 1,943), 파일 main 1,165·test 476.

숫자 요약: 기능 27개 묶음, 운영 사용자 실질 1명(ADMIN) + 가계부만 2명. 최근 30일 쓰기가 확인되는 기능은 전략 4종·DCA·투자금 주입·가계부·벤치마크·공포탐욕이며, 수동 체결 보정·관리자 정정은 마지막 사용이 2026-07-02.
trading 코드 줄 수의 상당 부분(test 54,104줄 중 `trading` 모듈이 27,954)은 전략 종류 공통 골격이라 기능별로 쪼갤 수 없다.
미확인: 무상태 기능(통계·백테스트·포트폴리오·수동 매매)의 실제 호출 횟수 — 접근 로그·메트릭이 없다.

## E. 유지가 애매한 후보의 상세

공통: 90일 커밋 수는 `git log --since=90.days --format=%h -- <해당 파일들> | sort -u | wc -l`(전체 90일 커밋 1,111개). 90일 오류는 `app_error_logs`(30일 99행, 90일 합계는 아래 해석)에서 해당 기능을 지목할 수 있는 것만 센다. **`app_error_logs`는 기능 태그가 없어(context가 `caller`·`pathname`뿐) 대부분 기능별 귀속이 불가능하다.** 지목 가능한 90일 오류 전체: Toss 점검·429 8건(2026-09-19, 10-14), root→trading 휴장일 조회 I/O 오류 9건(배포 창), KIS 주문가능금액 초과 2건(10-30), 배치 재개 알림 6건, 나머지는 UI 뷰포트·청크 오류.

### E-① PRIVACY·FIDA 연동

| 항목 | 값 |
|---|---|
| 코드 | main 2115줄(51파일) / test 1917줄(15파일). 핵심: `TC/privacy` 1054 / 781, root admin 쪽 `AdminPrivacyTrade*`·`PrivacyQueryHttpAdapter` 약 400줄, matching `PrivacyStrategy`·`PrivacyPlan` |
| 지우면 함께 사라지는 분기·타입 | `StrategyType.PRIVACY`(`SH/kernel/StrategyType.java:28`)와 `StrategyCapability.requiresPrivacyBase` 필드. main에서 `PRIVACY`를 단어로 담은 줄 157개(65파일), switch case 줄 5개(`StrategyCreationPersister.java:64,80`·`BacktestEngine.java:67`·`AdminSettingsRequest.java:89`·`PrivacyCreationResolver.java:12`). 그 밖에 `Strategy.isPrivacy()`(`Strategy.java:41`), `PrivacyBaseGuard`·`PrivacyCycleOrderStrategy`, `TradingPolicySettings` PRIVACY 기본값(`:37`), 백테스트의 PRIVACY 경로(`BacktestEngine.java:74,293,341,349`), `CapitalLedgerService.java:184`의 "PRIVACY·INFINITE·DCA" 허용 목록, `GlobalExceptionHandler`·`TradingExceptionHandler`·`InternalApiStatusHandlers`의 `PrivacyTradeConflictException` 매핑, `PrivacyAlertRaisedEvent` 알림, admin 화면 `/admin/privacy-trades`, DB 2개 테이블 |
| 스케줄러·외부 호출 | 스케줄러 없음. FIDA(별도 레포)가 `POST /api/internal/fida-orders`를 공인 도메인으로 push(`FidaOrderController.java:37`). KISTA는 FIDA를 호출하지 않는다. 외부 호출 비용 0 |
| 운영 사용 | 활성 전략 2개(MERITZ 1·MOCK 1), 사용자 1명. 기준표 `privacy_trade_bases` 377행, 최근 수신 2026-11-11 22:01. 30일 주문 131건(메리츠 56·MOCK 75). 과거 KIS 47건(마지막 2026-07-09)·TOSS 278건(마지막 2026-09-21)은 끝남. 감사 `PRIVACY_BASE_CREATE` 1·`PRIVACY_BASE_UPDATE` 11(90일, 최근 2026-10-30) — 운영자가 기준표를 손으로 고친다 |
| 90일 오류·커밋 | 지목 가능한 오류 0(`PrivacyTradeConflictException` 3건은 2026-06-29로 90일 밖). 커밋 21개 |

### E-② 백테스트

| 항목 | 값 |
|---|---|
| 코드 | main 1086줄(11파일) / test 2211줄(5파일). 엔진 `TC/stats/domain/backtest` 730 / 1486 |
| 지원 범위 | **VR·INFINITE 지원, PRIVACY는 기준표 맵을 받는 3-인자 `run`으로만 지원, DCA 미지원.** 근거 `BacktestEngine.java:61-68`(2-인자 `run`의 `case PRIVACY, DCA -> throw`), `:74`(PRIVACY 오버로드), `BacktestService.java:92-94`(DCA 거절). 종목은 전략 capability 부분집합만(`BacktestService.java:98`) |
| 다른 모듈에 생긴 것 | `FillSimulator`(백테스트 소속)를 **MOCK 브로커가 재사용**한다(`MockBrokerAdapter.java`·`FillSimulator.java`) — 백테스트를 지워도 `FillSimulator`는 남아야 한다. `BacktestService`가 `PrivacyTradePort`(`stats`→`privacy` 모듈 의존)와 `StrategyCreationPolicyPort`(VR 허용값)를 쓴다 |
| 스케줄러·외부 호출 | 스케줄러 없음. 요청마다 Alpaca에서 과거 일봉 조회(`HistoricalCandlePort`, `AlpacaCandleAdapter.java:64`에 "페이지네이션 미구현" 경고) — 호출 1회당 외부 비용 |
| 운영 사용 | 무상태라 호출 흔적 없음. ui `/stats/backtest` 화면·`features/backtest` 존재. 미확인: 실제 사용 횟수(접근 로그 없음) |
| 90일 오류·커밋 | 지목 오류 0. 커밋 37개(전체의 3.3%, 주제 대비 높음 — 2026-08~11 계산 개선) |

### E-③ 벤치마크(주택/ETF)

| 항목 | 값 |
|---|---|
| 코드 | main 2437줄(63파일) / test 2934줄(20파일) — `API/benchmark` 전체 |
| 다른 모듈에 생긴 것 | 투자 지수: trading 쪽 `InvestmentPointsQueryService`(264 / 478)와 내부 API 2개(`/api/internal/trading/stats/investment-points`, `/stats/exchange-rate`)는 **벤치마크만을 위해** 존재(호출처 `InvestmentPointsHttpAdapter.java:29`·`CurrentExchangeRateHttpAdapter.java:26`). `admin_runtime_settings.benchmarks.etf`(허용 SPY·QQQ·QLD·IBIT·ETHA, 기본 QLD)와 `AdminSettingsRequest`의 benchmarks 필드, `BenchmarkAlertRaisedEvent`(`AlertNotifier.java:26`), 스케줄러 수동 트리거 4개, DB 3개 테이블, `BrokerStatisticsPort`의 환율 |
| 스케줄러·외부 호출 | 스케줄러 4개(KB Land 토 08:00·08:10, 매월 1일 08:20 / ETF 종가 매일 09:00). 외부: KB Land, Alpaca(IEX) |
| 운영 사용 | 데이터 쓰기는 정상: ETF 종가 최근 2026-11-12 09:00, KB 지수 11-06, 주택 10-30. 테이블 합계 4만 6천 행. 조회 사용자·횟수는 미확인(접근 로그 없음). ui `/stats/benchmark` |
| 90일 오류·커밋 | 지목 오류 0(수집 실패 알림은 `app_error_logs`에 안 남는 관리자 텔레그램 경로). 커밋 14개 |

### E-④ 공포탐욕지수·코인 시장 지표

| 항목 | 값 |
|---|---|
| 코드 | 공포탐욕 main 938줄(37파일) / test 968줄(10파일), 코인 지표·포트폴리오 919 / 1088(12 / 5파일) |
| 중복 | 공포탐욕 수집기가 **두 벌**이다 — root `CnnFearGreedAdapter`(41줄)·`CryptoFearGreedAdapter`(65줄)와 trading-core `CnnFearGreedIndexAdapter`(42줄)·`CryptoFearGreedIndexAdapter`(43줄)(DCA 조건용, `TradingFearGreedConfig`). 같은 CNN·alternative.me를 두 프로세스가 각자 호출 |
| 다른 모듈에 생긴 것 | `DcaIndicator.FEAR_GREED`(`SH/kernel/DcaIndicator.java`, DB `strategy_dca_condition.indicator` 직결)와 `DcaPlanInputCollector`·`CryptoDcaRoundExecutor`·`DcaConditions`의 공포탐욕 평가, `FearGreedFetchFailedEvent`→관리자 알림 |
| 스케줄러·외부 호출 | 공포탐욕 스케줄러 1개(07:00·12:00), 외부 CNN·alternative.me. 코인 지표는 요청 시 외부 7곳(Binance·Coinbase·CoinMarketCap·mempool.space·Frankfurter·Upbit·Alpaca)을 호출하고 10분 캐시(`CryptoMarketIndicatorService.java:43`), **Upbit 시세를 이 기능이 쓴다**(`UpbitMarketDataPort`, 업비트 브로커 삭제와 별개로 남음) |
| 운영 사용 | 공포탐욕 `fear_greed_snapshots` 1640행, 최근 2026-11-12 12:00. DCA 조건 지표로 실제 쓰는지는 `strategy_dca_condition` 19행의 indicator 분포로 확인 가능하나 조회하지 않음(미확인). 코인 지표는 무상태, 비인증 공개 엔드포인트(`/api/crypto-market-indicators`) |
| 90일 오류·커밋 | 지목 오류 0. 커밋 45개(`market`+`CryptoMarketIndicator` 합산) |

### E-⑤ 가계부(`api.finance`)

| 항목 | 값 |
|---|---|
| 코드 | main 5024줄(112파일) / test 5441줄(27파일) — **root main의 30%**(16,495줄 중). 엔드포인트 42개(전체 176개의 24%), 테이블 9개 |
| 다른 모듈에 생긴 것 | `NotificationType.FINANCE_REMINDER`(`NotificationType.java:8`), `FinanceReminderChannelNotifier`, 사용자 삭제 cascade(`UserCascadeDeleter`·`UserEventStreamPublisher`), `MetaController`·`MetaBundle`·`GlobalExceptionHandler`·`UserEventResubmitScheduler`의 가계부 항목, Flyway V1·V3, `ErrorCode` 일부. 외부 호출 없음 |
| 스케줄러 | 1개(월말 21:00 리마인더, 마지막 2026-10-31) |
| 운영 사용 | **사용자 2명**(거래 2·그룹 멤버 2·자산 스냅샷 1). 거래 804행(삭제 88), 최근 2026-11-11 14:31, 30일 신규 39건(수정 41). 자산 스냅샷 1056행(30일 44건). 그룹 3·초대 4·멤버 4는 **마지막 2026-08-22**(83일 전), 예산 37행 마지막 09-02, 카테고리 75행 마지막 09-07. 월 마감 21행 마지막 11-01 |
| 90일 오류·커밋 | 지목 오류: UI `/finance` TypeError 2건. 커밋 12개(가장 안정적) |

### E-⑥ MOCK 브로커

| 항목 | 값 |
|---|---|
| 코드 | main 339줄(4파일: `MockBrokerAdapter`·`MockAuthApi`·`MockSimulationDataAdapter`·`MockSimulationDataPort`) / test 500줄(2파일). 분기: `Broker.MOCK`·`MOCK` 단어 17파일 31줄 |
| 다른 모듈에 생긴 분기 | `AccountService.java:63`(합성 계좌번호·키), `:135`(키 교체 거부), `TradingPreviewService.java:207,237`(예수금 점검 제외), `UnassignedCashCalculator.java:33`, `TradingStatsService.java:141,184`·`InvestmentPointsQueryService.java:60`(통계·투자지수에서 제외), `Broker.java`(enum), `Market.java`, `TossPriceApi`·`BrokerPricePort`·`BrokerStatisticsPorts`의 MOCK 언급, 백테스트와 공유하는 `FillSimulator`, DTO `AccountRequest`·`CredentialsRequest` |
| 스케줄러·외부 호출 | 없음. DB 기반 모의 체결(`FillSimulator`)로 `MockSimulationDataPort`가 일봉을 읽는다 |
| 운영 사용 | **운영에 MOCK 계좌 1개에 활성 전략 4개(INFINITE·VR·PRIVACY·DCA 전부)**, 30일 주문 529건(전체 1,132건의 47%), 최근 2026-11-12. 삭제된 MOCK 계좌 5개(누적 생성·폐기 반복). 사용자 1 |
| 90일 오류·커밋 | 지목 오류 0. 커밋 18개 |

### E-⑦ 수동 체결 보정

| 항목 | 값 |
|---|---|
| 코드 | main 592줄(11파일) / test 754줄(3파일): `ManualTradeCorrectionService`(221)·`ManualTradeCorrectionUseCase`·command·result, root `AdminTradeCorrectionService`·`AdminTradeController`, internal `TradingInternalCommandController` |
| 다른 모듈에 생긴 것 | `ExecutionFillMapper`·`CloseReportScope`·`CycleEndedNotifier`·`Execution`·`TradingService`·`StrategyService`·`CapitalLedgerService`가 보정 경로를 언급(파일 12개), 내부 API `POST /api/internal/trading/trade-corrections`, 관리자 `POST /api/admin/trades/manual-fills`, 감사 `ORDER_CORRECTION` |
| 지원 범위 | 종료된 사이클은 미지원(`ManualTradeCorrectionService.java:79`). 마감 리포트 윈도우·중복 체결·리포트 이전 미식별 체결은 거절(`:70-72` 부근) |
| 운영 사용 | ui 호출 0(관리자 화면 없음). 감사 로그 누적 **2건, 마지막 2026-07-02**, 90일 0건. 사용자 1 |
| 90일 오류·커밋 | 지목 오류 0. 커밋 29개(코드는 계속 손봤는데 사용은 멈춘 상태) |

### E-⑧ 관리자 런타임 설정

| 항목 | 값 |
|---|---|
| 코드 | main 1171줄(28파일) / test 1583줄(15파일). 엔드포인트 5개(`GET·PUT /api/admin/settings`, `GET /api/runtime-config`, 내부 `policy-settings` GET·PUT) |
| 다른 모듈에 생긴 것 | `TradingPolicySettings`·`StrategyCreationSettings`·`StrategyFieldSettings`·`BrokerSettings`(shared kernel — 전략별 ticker·분할 수·밴드 폭 등 허용값을 모양으로 가진 설정 트리), `AccountService`(브로커 허용 정책 소비)·`StrategyCreationService.java`(`resolveCreationSettings`)·`UserService`(승인 필요 여부)·`BenchmarkService`(ETF 허용값) 4곳이 읽는다. 두 서비스(root `admin_runtime_settings`, trading `trading_runtime_settings`)에 설정 저장소가 둘로 나뉘고 root→trading 내부 API로 위임 |
| 스케줄러·외부 호출 | 없음. 내부 HTTP 1홉(PUT) |
| 운영 사용 | 각 저장소 1행. 감사 `RUNTIME_SETTINGS_UPDATE` 누적 20, 90일 8, 마지막 2026-10-26. 현재 값: 가입 승인 불필요(`approvalRequired=false`), KIS·UPBIT 비활성, 나머지 활성, 전략 4종 활성 |
| 90일 오류·커밋 | 지목 오류 0. 커밋 18개 |

### E-⑨ D에서 드러난 "사용 흔적이 거의 없는" 다른 기능

| 후보 | 근거 |
|---|---|
| 가입 승인 흐름(PENDING·REJECTED, 텔레그램 승인 버튼, 상태 SSE, 재신청) | `approvalRequired=false`라 자동 승인. 가입 30일 0, 승인 감사 로그 1건(2026-06-17). ui `/pending`·`/rejected`·`/admin/pending`, `TelegramBotService.java:59`, `SseEmitterRegistry`, `UserService` 재신청 분기는 현재 설정에서 타지 않는다 |
| 투자금 인출 | `cycle_capital_event` WITHDRAW 0건(INJECT 5건). `CapitalWithdrawalService`(35줄)+`CapitalLedgerService` 인출 경로 |
| 가계부 그룹 공유(그룹·초대·멤버, 항목별 share/unshare 14개) | 그룹 3·초대 4·멤버 4, 마지막 2026-08-22. share·unshare 엔드포인트 14개는 ui 호출은 있으나 쓰기 흔적 미확인 |
| 관리자 정정(전략 일시정지·재개, 주문 보정) | 감사 로그 4건 전부 2026-07-02 이전 |
| 관리자 수동 스케줄러 트리거 6개·`/api/admin/_ping` | ui 호출 0 |
| 토스 전략(INFINITE·PRIVACY) | 활성 전략 0, 주문은 2026-08-04(INFINITE)·09-21(PRIVACY) 이후 0. 계좌 1개만 남음(사용자 결정: 토스 주문 유지) |
| 업비트 | 계좌 1개, 전략 0, 정책 enabled=false(삭제 결정 사항) |
| 수동 매매(바로 주문) | 전용 이력이 없어 미확인 |

숫자 요약: 후보 8개의 main 합계 약 1.3만 줄(PRIVACY 2115·백테스트 1086·벤치마크 2437·공포탐욕/코인지표 1857·가계부 5024·MOCK 339·보정 592·런타임 설정 1171, 겹침 있음)이고 test는 약 1.6만 줄.
운영 사용 정지·정체: 수동 체결 보정(마지막 2026-07-02), 관리자 정정(2026-07-02), 가계부 그룹(2026-08-22), 가입 승인(2026-06-17). 현재도 도는 것: PRIVACY·VR·INFINITE·DCA·벤치마크 수집·공포탐욕·가계부 거래.
MOCK 계좌가 운영 주문의 47%를 만든다(운영 DB가 개발 검증 환경을 겸함). 공포탐욕 수집기는 두 프로세스에 중복.

## F. 지원 매트릭스의 빈칸

근거는 모두 main 소스의 미지원 분기다. `UnsupportedOperationException`은 main에 0건, `TODO`·`FIXME`도 0건이다(grep). 미지원은 전부 `IllegalArgumentException`·전용 예외로 표현돼 있다.

### F-1. 기능 × 전략 종류 (X=미지원, O=지원)

| 기능 | INFINITE | VR | PRIVACY | DCA(주식) | DCA(코인) | 근거 `file:line` |
|---|---|---|---|---|---|---|
| 코인 계좌에 전략 등록 | X | X | X | - | O | `TC/trading/application/service/StrategyCreationService.java:76-77` "코인 계좌는 DCA 전략만" |
| 수동 바로 주문 | O | O | O | X | X | `ManualTradingService.java:57` `DcaManualExecutionUnsupportedException` |
| 주문 미리보기 | O | O | O | O | X | `TradingPreviewService.java:78`(코인 계좌 거절) |
| 백테스트 | O | O | O(기준표 맵 필요) | X | X | `BacktestEngine.java:67`, `BacktestService.java:92-94` |
| 투자금 추가·인출 | O | X | O | O | O | `CapitalLedgerService.java:78,179,184` "PRIVACY·INFINITE·DCA만" |
| VR 재설정(`vr-config`) | X | O | X | X | X | `VrReconfigureService.java:69` |
| DCA 설정·실행 기록 조회 | X | X | X | O | O | `DcaConfigService.java:40`, `DcaExecutionQueryService.java:40` |
| 연속 사이클 정책(cycleSeedType) | O | NONE 고정 | O | NONE 고정 | NONE 고정 | `StrategyCreationService.java:113`, `StrategyUpdateWriter.java:66` |
| 리버스모드 | O | X | X | X | X | `StrategyCapability.supportsReverseMode`(INFINITE만 true) |
| 수동 체결 보정 | 종료된 사이클만 X(전 종류 공통) | 〃 | 〃 | 〃 | 〃 | `ManualTradeCorrectionService.java:79` |

### F-2. 기능 × 브로커

| 기능 | TOSS | KIS | MERITZ | MOCK | BINANCE | UPBIT | 근거 |
|---|---|---|---|---|---|---|---|
| 개장 경매 주문(MOO/LOO) 선접수 | X(개장 후 지정가 근사) | O | X | X | - | - | `SH/kernel/Broker.java` `supportsOpenAuctionOrders()`(KIS만), `TradingService.java:380` |
| MARKET 주문 | X(LIMIT+CLS 대체) | X | X | - | O | O | `TossOrderApi.java:42,178-180`, `MeritzOrderApi.java:79`, `KisOrderApi.java:159` |
| 캔들·환율 통계 포트(`BrokerStatisticsPort`) | O(유일) | X | X | X | X | X | `BrokerStatisticsPort.java:5`, `BrokerStatisticsService.java:16` |
| 주문 재전송(SUBMITTING 잔류) | O | X | X | X | X | X | `OrderSubmissionReconciler.java:55` |
| 키 교체 | O | O | O | X | O | O | `AccountService.java:135` |
| 통계·투자지수 포함 | O | O | O | X | O | O | `TradingStatsService.java:141,184`, `InvestmentPointsQueryService.java:60` |
| 예수금 부족 사전 점검(주식 미수 방지) | O | O | O | X | X | X | `TradingPreviewService.java:207,237` |
| 주식 포트폴리오 화면 / 코인 포트폴리오 | 주식 | 주식 | 주식 | 주식 | 코인 | 코인 | `CryptoPortfolioService.java:76-77`(코인 아니면 거절) |
| 야간 배치(개장·마감) 대상 | O | O | O | O | X(10분 틱) | X(10분 틱) | `BatchContextFactory.java:51`, `CryptoDcaRunner.java:62` |
| 계좌번호 | 입력 | 입력 | 불필요 | 합성 | 불필요 | 불필요 | `AccountService.java:63-70` |
| API 키 만료일 | 추정 | 추정 | 추정 | 없음 | 없음 | 조회 | `Broker.java` `ApiKeyExpirySource` |

종류×브로커 빈칸: 코인 거래소 계좌는 DCA만(위 F-1 첫 행). 운영에서 실제로 채워진 칸은 BINANCE×DCA, KIS×INFINITE, MERITZ×VR·PRIVACY, MOCK×4종뿐이다(D-0).
종류별로 걸러 건너뛰는 분기: 코인 계좌는 개장·마감 배치에서 건너뜀(`BatchContextFactory.java:51`), 코인은 주문 취소 경로가 별도(`OrderCancelService.java:59,82,98,168`).

### F-3. 미완 표시 grep (main 소스)

| 위치 | 내용 | 판정 |
|---|---|---|
| `TC/stats/adapter/out/alpaca/AlpacaCandleAdapter.java:64` | "페이지네이션 미구현이라 데이터 누락 가능" 경고 | 실제 미구현(백테스트 장기 구간에서 발생 가능) |
| `TC/notify/adapter/out/gateway/TradingReportNotifier.java:22` | "구현체는 후속 태스크에서 채움" | 이미 구현됨 — 낡은 주석 |
| `TC/notify/application/port/output/TradingRealtimeNotificationPort.java:8` | "구현체는 후속 태스크가 채운다 — 지금은 인터페이스만" | 구현체 `RedisTradeEventPublisher` 존재 — 낡은 주석 |
| `TC/notify/application/port/output/TradingUserNotificationPort.java:18` | "구현체는 후속 태스크가 채운다" | 구현체 존재 — 낡은 주석 |
| `API/benchmark/.../KbLandHousingBenchmarkScheduler.java:14` | 주석 "일요일 07:00"인데 cron은 토 08:00 | 문서 드리프트(B절) |
| `TC/matching/domain/model/StrategyVrDetail.java:10` | 문서 경로 참조 | 미완 아님 |

`TODO`·`FIXME`·"추후"·"미구현" 중 실제 미구현은 Alpaca 페이지네이션 1건이다.

숫자 요약: 전략 종류 미지원 분기 10행 중 DCA가 가장 많이 걸린다(수동 주문·VR 재설정·백테스트·미리보기(코인)). 브로커 미지원은 토스만 통계 포트를 갖고 KIS만 개장 경매를 지원한다.
`UnsupportedOperationException` 0건, `TODO`·`FIXME` 0건, 낡은 "후속" 주석 3건, 실제 미구현 1건.

## G. 절반만 연결된 것

### G-1. API는 있으나 ui 호출 0 (A절 근거)

| 엔드포인트 | 성격 |
|---|---|
| `POST /telegram/webhook` | 텔레그램이 호출(정상) |
| `GET /api/admin/_ping` | ui·호출처 없음 |
| `GET /api/admin/accounts/{accountId}/strategies/{strategyId}/trade-dates` | root에 컨트롤러가 있으나 ui 호출 0 |
| `POST /api/admin/trades/manual-fills` | ui 화면 없음, 감사 로그 2건(2026-07-02) |
| `POST /api/admin/scheduler/{open,close,kbland-housing-benchmark,kbland-price-index,kbland-price-index/full-refresh,market-index-prices}` | 수동 트리거 6개, ui 호출 0 |
| `POST /api/auth/dev-*` 4개 | local 프로파일 전용 |

ui 호출 0은 서버 간 내부 30개를 빼고 14개다.

### G-2. 서버 간 내부 API 중 호출처가 없는 것

`GET /api/internal/marketcalendar/is-open`(`TC/marketcalendar/adapter/in/web/MarketCalendarInternalController.java:36`) — root main·shared·ui 어디에도 호출이 없고 테스트(`MarketCalendarInternalControllerTest.java:67`)에서만 쓴다. 나머지 29개 내부 엔드포인트는 root의 `*HttpAdapter`가 경로 문자열로 호출한다(경로 문자열 grep으로 확인).

### G-3. ui에는 있으나 API에 없는 경로

kista-ui 비-테스트 소스의 `/api/...` 리터럴 103개를 176개 엔드포인트 패턴과 대조했다. 대응 API가 없는 것은 `/api/auth/reapply-done`(`kista-ui/entities/user/api/index.ts`) 1개이며, 이는 ui 자체 Next 라우트(`app/api/auth/reapply-done/route.ts`)가 `POST /api/auth/approval-requests`로 넘기는 것이라 API 누락이 아니다. 따라서 ui→API 끊김은 0건이다.

### G-4. 진입점 없는 서비스

`application/service`·`usecase`·`domain/service` 파일을, `adapter/in/**`·`@Scheduled`·`@EventListener`·`@TransactionalEventListener`·`@PostConstruct`·`@Bean`·`@Configuration`에서 시작하는 타입 참조 그래프(인터페이스→구현체 포함)로 따라갔다. 전체 1,099개 main 파일 중 1,089개가 닿았고, 못 닿은 10개는 `adapter/in`이 아닌 위치의 컨트롤러·DTO·설정(`AdminSchedulerController`·`MetaController`·`web/dto/*Meta`·`PlatformAutoConfiguration`·`ErrorCodeOpenApiCustomizer`)뿐이다. **진입점이 없는 서비스·유스케이스는 0개**, 어디서도 주입·사용되지 않는 usecase 인터페이스도 0개다. 정적 참조 기준이라 "닿지만 런타임에 안 타는 분기"(예: 가입 승인 흐름, E-⑨)는 잡지 못한다.

### G-5. 그 밖의 반쪽 연결

| 항목 | 근거 |
|---|---|
| 락 테이블 죽은 행 7개(코드에 없는 락 이름) | B절 |
| root 쪽 Redis 복구 `@Scheduled` 2개가 `scheduler.enabled` 조건 없이 모든 root role에서 돈다 | `PushNotificationStreamConsumer.java:54`, `AppErrorStreamConsumer.java:55` |
| 공포탐욕 수집기가 root·trading-core에 중복 | E-④ |
| 가입 승인 흐름(화면·텔레그램 버튼·SSE)이 `approvalRequired=false`로 현재 비활성 | E-⑨ |
| `event_publication` 완료 행이 root 833·trading 335개 쌓여 있음(미완료 0) | 운영 SELECT. 정리 정책 확인은 미확인 |

숫자 요약: ui 호출 0 엔드포인트 14개(내부 30개 제외), 호출처 없는 내부 엔드포인트 1개, ui→API 끊김 0, 진입점 없는 서비스 0.
미확인: 런타임에 실제로 타지 않는 코드 경로(커버리지 없음), `event_publication` 정리 여부.
