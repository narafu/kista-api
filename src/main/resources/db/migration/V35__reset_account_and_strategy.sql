-- 계좌·전략 분리 리팩토링: 기존 데이터 초기화
-- 의존성 순서대로 TRUNCATE: 자식 테이블 먼저, 부모 테이블 나중
TRUNCATE TABLE orders, trade_histories, portfolio_snapshots,
               kis_tokens, strategies, accounts
RESTART IDENTITY CASCADE;
