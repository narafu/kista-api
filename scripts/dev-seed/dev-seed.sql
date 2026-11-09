-- 로컬 전용 dev 시드 — dev 유저(00000000-...-0001)·[시드] 계좌에만 적용, 재실행 가능(멱등)
-- 실행: scripts/dev-seed/seed.sh (dev-token 호출·MOCK 계좌/전략 API 등록 후 이 파일을 적용)
-- id는 md5('dev-seed:...') 결정적 UUID + ON CONFLICT DO NOTHING — 날짜는 KST 오늘 기준 상대값이라 재실행 시 최신 구간만 추가된다
\set ON_ERROR_STOP on
BEGIN;

-- 안전장치: dev 유저가 없으면 중단 (dev-token을 먼저 호출해야 함)
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.users WHERE id = '00000000-0000-0000-0000-000000000001') THEN
        RAISE EXCEPTION 'dev 유저 없음 — POST /api/auth/dev-token 먼저 호출';
    END IF;
END $$;

-- ── 가계부: 증권 계좌 1개 ──
INSERT INTO finance.finance_accounts (id, user_id, account_type, name, institution, owner)
VALUES (md5('dev-seed:fin-account')::uuid, '00000000-0000-0000-0000-000000000001',
        'SECURITIES', '[시드] 해외주식 계좌', '한국투자증권', '본인')
ON CONFLICT (id) DO NOTHING;

-- ── 가계부: 자산 스냅샷 — 최근 4개월 1일자, 예적금·투자·부동산·대출 ──
INSERT INTO finance.finance_asset_snapshots (id, category_id, account_id, user_id, entry_date, asset_class, market, strategy, memo, amount)
SELECT md5('dev-seed:snapshot:' || m.month || ':' || s.code)::uuid,
       ('f1000000-0000-4000-8000-000000000' || s.code)::uuid,
       CASE WHEN s.code = '403' THEN md5('dev-seed:fin-account')::uuid END,
       '00000000-0000-0000-0000-000000000001', m.month, s.asset_class, s.market, s.strategy, s.memo,
       s.base + s.step * m.i
FROM (SELECT i, (date_trunc('month', (now() AT TIME ZONE 'Asia/Seoul')::date) - make_interval(months => 3 - i))::date AS month
      FROM generate_series(0, 3) i) m
CROSS JOIN (VALUES ('401', 'CASH',        'DOMESTIC', NULL, '비상금',   12000000,  500000),
                   ('403', 'EQUITY',      'GLOBAL',   'VR', 'TQQQ 적립', 35000000, 1800000),
                   ('402', 'REAL_ESTATE', 'DOMESTIC', NULL, NULL,      420000000,       0),
                   ('404', 'CASH',        'DOMESTIC', NULL, '주담대',  180000000, -1000000))
    AS s(code, asset_class, market, strategy, memo, base, step)
ON CONFLICT (id) DO NOTHING;

-- ── 가계부: 거래 — 최근 12개월 × 수입·소비·저축 7건 ──
INSERT INTO finance.finance_transactions (id, category_id, user_id, transaction_date, amount, memo)
SELECT md5('dev-seed:tx:' || m.month || ':' || t.code)::uuid,
       ('f1000000-0000-4000-8000-000000000' || t.code)::uuid,
       '00000000-0000-0000-0000-000000000001',
       m.month + t.day_offset, t.amount + m.i * 1000, t.memo
FROM (SELECT i, (date_trunc('month', (now() AT TIME ZONE 'Asia/Seoul')::date) - make_interval(months => 11 - i))::date AS month
      FROM generate_series(0, 11) i) m
CROSS JOIN (VALUES ('111', 4, 4200000, '월급'), ('122', 5, 85000, '배당'), ('201', 6, 1100000, '관리비'),
                   ('202', 7, 640000, '장보기'), ('203', 8, 300000, NULL), ('301', 9, 500000, 'DCA 적립'),
                   ('302', 10, 100000, NULL))
    AS t(code, day_offset, amount, memo)
WHERE m.month + t.day_offset <= (now() AT TIME ZONE 'Asia/Seoul')::date -- 아직 오지 않은 날짜는 생략, 재실행 시 추가
ON CONFLICT (id) DO NOTHING;

-- ── 가계부: 예산 — 생활비·주거비, 올해 1월부터(id가 연도별이라 해가 바뀌면 새 행 추가) ──
INSERT INTO finance.finance_budgets (id, category_id, user_id, apply_start_date, amount)
SELECT md5('dev-seed:budget:' || b.code || ':' || extract(year FROM (now() AT TIME ZONE 'Asia/Seoul')))::uuid, ('f1000000-0000-4000-8000-000000000' || b.code)::uuid,
       '00000000-0000-0000-0000-000000000001', date_trunc('year', (now() AT TIME ZONE 'Asia/Seoul')::date)::date, b.amount
FROM (VALUES ('202', 700000), ('201', 1000000)) AS b(code, amount)
WHERE NOT EXISTS (SELECT 1 FROM finance.finance_budgets fb -- 같은 연초 시작 예산이 이미 있으면(이전 id 체계 포함) 생략
                  WHERE fb.user_id = '00000000-0000-0000-0000-000000000001'
                    AND fb.category_id = ('f1000000-0000-4000-8000-000000000' || b.code)::uuid
                    AND fb.apply_start_date = date_trunc('year', (now() AT TIME ZONE 'Asia/Seoul')::date)::date)
ON CONFLICT (id) DO NOTHING;

-- ── 누적자산추이(equity-curve)용: KIS [시드] 전략(PAUSED)의 진행 중 사이클에 평일 일별 포지션 스냅샷 ──
-- equity-curve는 MOCK 계좌를 제외하므로 KIS 시드 사이클에 쌓는다. PAUSED라 스케쥴러가 건드리지 않는다
-- 스냅샷 시각은 04:30 KST 마감 배치와 동일, 보유 100주·평단 30 고정, 종가만 완만한 사인파로 변동
-- 전제: KIS [시드] 계좌·전략은 바로 아래 블록이 만든다(자격증명이 가짜라 재개(resume) 금지)
-- KIS [시드] 계좌: API 등록은 KIS 실연결 검증(verifyAccount)이라 불가 — MOCK [시드] 계좌의 AES 암호문(로컬 키로 암호화된 가짜 값)을 복사한다
-- account_no_hash는 NULL(부분 UNIQUE 인덱스 대상 밖). MOCK 계좌가 없으면(API 등록 실패) 생략된다
INSERT INTO trading.accounts (id, user_id, nickname, broker, account_no, app_key, secret_key)
SELECT md5('dev-seed:kis:account')::uuid, a.user_id, '[시드] KIS 계좌', 'KIS', a.account_no, a.app_key, a.secret_key
FROM trading.accounts a
WHERE a.user_id = '00000000-0000-0000-0000-000000000001' AND a.broker = 'MOCK'
  AND a.nickname LIKE '[시드]%' AND a.deleted_at IS NULL
ORDER BY a.created_at LIMIT 1
ON CONFLICT (id) DO NOTHING;

-- KIS [시드] 전략: INFINITE SOXL PAUSED, 60일 전 시작한 진행 중 사이클 + 개장 포지션(equity-curve 기준 예수금)
INSERT INTO trading.strategy (id, account_id, type, ticker, status, cycle_seed_type)
SELECT md5('dev-seed:kis:strategy')::uuid, a.id, 'INFINITE', 'SOXL', 'PAUSED', 'NONE'
FROM trading.accounts a WHERE a.id = md5('dev-seed:kis:account')::uuid
ON CONFLICT (id) DO NOTHING;

INSERT INTO trading.strategy_version (id, strategy_id, version_no)
SELECT md5('dev-seed:kis:version')::uuid, s.id, 1
FROM trading.strategy s WHERE s.id = md5('dev-seed:kis:strategy')::uuid
ON CONFLICT DO NOTHING;

INSERT INTO trading.strategy_infinite_version (strategy_version_id, division_count)
SELECT v.id, 20
FROM trading.strategy_version v WHERE v.id = md5('dev-seed:kis:version')::uuid
ON CONFLICT DO NOTHING;

INSERT INTO trading.strategy_cycle (id, strategy_id, strategy_version_id, start_amount, open_amount, start_date, created_at)
SELECT md5('dev-seed:kis:cycle')::uuid, v.strategy_id, v.id, 6000, 6000, d.start_date, d.start_date::timestamp AT TIME ZONE 'Asia/Seoul'
FROM trading.strategy_version v
CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE 'Asia/Seoul')::date - 60 AS start_date) d
WHERE v.id = md5('dev-seed:kis:version')::uuid
ON CONFLICT (id) DO NOTHING;

INSERT INTO trading.cycle_position (id, strategy_cycle_id, deposit, closing_price, avg_price, holdings, created_at)
SELECT md5('dev-seed:kis:pos:open')::uuid, c.id, 3000, 30.00, NULL, 0, c.created_at
FROM trading.strategy_cycle c WHERE c.id = md5('dev-seed:kis:cycle')::uuid
ON CONFLICT (id) DO NOTHING;

INSERT INTO trading.cycle_position_infinite (cycle_position_id, is_reverse_mode)
SELECT p.id, false FROM trading.cycle_position p WHERE p.id = md5('dev-seed:kis:pos:open')::uuid
ON CONFLICT (cycle_position_id) DO NOTHING;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM trading.accounts WHERE user_id = '00000000-0000-0000-0000-000000000001'
                   AND broker = 'KIS' AND nickname LIKE '[시드]%' AND deleted_at IS NULL) THEN
        RAISE NOTICE 'KIS [시드] 계좌 없음 — equity-curve 포지션·관리자 주문 시드 생략';
    END IF;
END $$;

CREATE TEMP TABLE seed_positions ON COMMIT DROP AS
SELECT md5('dev-seed:pos:' || c.id || ':' || d::date)::uuid AS id, c.id AS cycle_id, s.type,
       p.deposit,
       round((30 + 6 * sin(extract(epoch FROM d) / 86400 / 9) + (d::date - c.start_date) * 0.02)::numeric, 2) AS closing_price,
       (d::date + time '04:30') AT TIME ZONE 'Asia/Seoul' AS created_at
FROM trading.strategy_cycle c
JOIN trading.strategy s ON s.id = c.strategy_id
JOIN trading.accounts a ON a.id = s.account_id
JOIN LATERAL (SELECT deposit FROM trading.cycle_position
              WHERE strategy_cycle_id = c.id AND deleted_at IS NULL ORDER BY created_at LIMIT 1) p ON true
CROSS JOIN generate_series(c.start_date + 1, (now() AT TIME ZONE 'Asia/Seoul')::date - 1, interval '1 day') d
WHERE a.user_id = '00000000-0000-0000-0000-000000000001' AND a.broker = 'KIS'
  AND a.nickname LIKE '[시드]%' AND s.status = 'PAUSED'
  AND c.end_date IS NULL AND c.deleted_at IS NULL AND s.deleted_at IS NULL AND a.deleted_at IS NULL
  AND extract(isodow FROM d) < 6;

INSERT INTO trading.cycle_position (id, strategy_cycle_id, deposit, closing_price, avg_price, holdings, created_at)
SELECT id, cycle_id, deposit, closing_price, 30.00, 100, created_at FROM seed_positions
ON CONFLICT (id) DO NOTHING;

INSERT INTO trading.cycle_position_infinite (cycle_position_id, is_reverse_mode)
SELECT id, false FROM seed_positions WHERE type = 'INFINITE'
ON CONFLICT (cycle_position_id) DO NOTHING;

-- ── 관리자 주문 관리용: KIS [시드] 전략의 최근 10 평일 종결 주문 ──
-- 종결 상태(FILLED·CANCELLED·FAILED)만 — PLACED/PLANNED는 KIS 대상 동기화·취소 경로가 실호출할 수 있어 넣지 않는다
-- 당일 PLACED 주문은 MOCK 전략을 스케쥴러가 실행하며 자연 생성된다
INSERT INTO trading.orders (id, account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction,
                            price, quantity, status, external_order_id, filled_quantity, filled_price)
SELECT md5('dev-seed:order:' || c.id || ':' || d::date || ':' || o.seq)::uuid,
       s.account_id, c.id, d::date, s.ticker, 'LOC', 'AT_CLOSE', o.direction,
       o.price, o.quantity, o.status,
       CASE WHEN o.status = 'FAILED' THEN NULL ELSE 'SEED-' || left(md5(c.id || d::text || o.seq), 10) END,
       CASE WHEN o.status = 'FILLED' THEN o.quantity END,
       CASE WHEN o.status = 'FILLED' THEN o.price END
FROM trading.strategy_cycle c
JOIN trading.strategy s ON s.id = c.strategy_id
JOIN trading.accounts a ON a.id = s.account_id
CROSS JOIN generate_series((now() AT TIME ZONE 'Asia/Seoul')::date - 14, (now() AT TIME ZONE 'Asia/Seoul')::date - 1, interval '1 day') d
CROSS JOIN (VALUES (1, 'BUY', 28.15, 4, 'FILLED'), (2, 'BUY', 27.50, 5, 'CANCELLED'),
                   (3, 'SELL', 30.97, 42, 'FAILED'))
    AS o(seq, direction, price, quantity, status)
WHERE a.user_id = '00000000-0000-0000-0000-000000000001' AND a.broker = 'KIS'
  AND a.nickname LIKE '[시드]%' AND s.status = 'PAUSED'
  AND c.end_date IS NULL AND c.deleted_at IS NULL AND s.deleted_at IS NULL AND a.deleted_at IS NULL
  AND extract(isodow FROM d) < 6
ON CONFLICT (id) DO NOTHING;

-- ── 사이클 성과 목록(status 필터·번호 페이징)용: MOCK [시드] 계좌에 INFINITE MAGX·USD 전략(PAUSED) ──
-- API 등록(SOXL·TQQQ)과 겹치지 않는 종목으로 종료 사이클 15·8건 + 진행 중 1건씩(합계 25건, size=20 기준 2페이지)
-- API 등록이 실패해도(예: Toss 허용 IP 밖이라 MOCK 시세 403) 이 데이터는 생성된다. PAUSED라 스케쥴러가 건드리지 않는다
CREATE TEMP TABLE seed_cycle_tickers (ticker, closed_n, price) ON COMMIT DROP AS
VALUES ('MAGX', 15, 45.30::numeric), ('USD', 8, 52.80::numeric);

INSERT INTO trading.strategy (id, account_id, type, ticker, status, cycle_seed_type)
SELECT md5('dev-seed:cycles:strategy:' || t.ticker)::uuid, a.id, 'INFINITE', t.ticker, 'PAUSED', 'NONE'
FROM trading.accounts a
CROSS JOIN seed_cycle_tickers t
WHERE a.user_id = '00000000-0000-0000-0000-000000000001' AND a.broker = 'MOCK'
  AND a.nickname LIKE '[시드]%' AND a.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM trading.strategy s -- 같은 종목 전략이 이미 있으면(직접 등록 포함) 생략
                  WHERE s.account_id = a.id AND s.ticker = t.ticker AND s.deleted_at IS NULL)
ON CONFLICT (id) DO NOTHING;

INSERT INTO trading.strategy_version (id, strategy_id, version_no)
SELECT md5('dev-seed:cycles:version:' || t.ticker)::uuid, s.id, 1
FROM seed_cycle_tickers t
JOIN trading.strategy s ON s.id = md5('dev-seed:cycles:strategy:' || t.ticker)::uuid
ON CONFLICT DO NOTHING;

INSERT INTO trading.strategy_infinite_version (strategy_version_id, division_count)
SELECT v.id, 20
FROM seed_cycle_tickers t
JOIN trading.strategy_version v ON v.id = md5('dev-seed:cycles:version:' || t.ticker)::uuid
ON CONFLICT DO NOTHING;

-- i = 1..closed_n은 3주 간격 종료 사이클(손익 -8%~+12%), i = closed_n+1은 2주 전 시작한 진행 중 사이클
INSERT INTO trading.strategy_cycle (id, strategy_id, strategy_version_id, start_amount, open_amount, end_amount, start_date, end_date, created_at)
SELECT md5('dev-seed:cycles:cycle:' || t.ticker || ':' || g.i)::uuid, v.strategy_id, v.id,
       CASE WHEN g.i > t.closed_n THEN 6000 ELSE 5000 + g.i * 120 END,
       CASE WHEN g.i > t.closed_n THEN 6000 ELSE 5000 + g.i * 120 END,
       CASE WHEN g.i > t.closed_n THEN NULL ELSE round((5000 + g.i * 120) * (1 + ((g.i * 37) % 21 - 8) / 100.0), 2) END,
       d.start_date,
       CASE WHEN g.i > t.closed_n THEN NULL ELSE d.start_date + 18 END,
       d.start_date::timestamp AT TIME ZONE 'Asia/Seoul'
FROM seed_cycle_tickers t
JOIN trading.strategy_version v ON v.id = md5('dev-seed:cycles:version:' || t.ticker)::uuid
CROSS JOIN generate_series(1, t.closed_n + 1) g(i)
CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE 'Asia/Seoul')::date - (t.closed_n + 1 - g.i) * 21 - 14 AS start_date) d
ON CONFLICT (id) DO NOTHING;

-- 진행 중 사이클의 개장·최신 포지션(미실현 손익 계산용) — 최신은 보유 30주·종가 +4%
CREATE TEMP TABLE seed_open_positions ON COMMIT DROP AS
SELECT md5('dev-seed:cycles:pos:' || t.ticker || ':' || p.kind)::uuid AS id, c.id AS cycle_id,
       p.deposit, round(t.price * p.price_rate, 2) AS closing_price,
       CASE WHEN p.holdings > 0 THEN t.price END AS avg_price, p.holdings,
       CASE WHEN p.kind = 'open' THEN c.created_at ELSE now() END AS created_at
FROM seed_cycle_tickers t
JOIN trading.strategy_cycle c ON c.id = md5('dev-seed:cycles:cycle:' || t.ticker || ':' || (t.closed_n + 1))::uuid
CROSS JOIN (VALUES ('open', 6000, 1.00, 0), ('latest', 4200, 1.04, 30)) AS p(kind, deposit, price_rate, holdings);

INSERT INTO trading.cycle_position (id, strategy_cycle_id, deposit, closing_price, avg_price, holdings, created_at)
SELECT id, cycle_id, deposit, closing_price, avg_price, holdings, created_at FROM seed_open_positions
ON CONFLICT (id) DO NOTHING;

INSERT INTO trading.cycle_position_infinite (cycle_position_id, is_reverse_mode)
SELECT id, false FROM seed_open_positions
ON CONFLICT (cycle_position_id) DO NOTHING;

-- ── 코인 계좌 통화별 합산(summary·곡선·사이클 성과) 검증용: BINANCE(USDT)·UPBIT(KRW) [시드] 계좌에 DCA 전략(PAUSED) ──
-- DCA는 원장 예수금 기반(CryptoLedgerRecorder) — 회차마다 deposit이 매수액만큼 줄고 holdings가 늘며 avg_price는 누적 매입원가 ÷ holdings
-- 120일 전 시작 사이클, 매일(주말 포함) 04:30 KST 스냅샷, 60일 전 INJECT 이벤트(start_amount·이후 deposit에 가산, open_amount 불변)
-- 시드 코인 계좌의 crypto-portfolio(실 거래소 잔고) 호출은 가짜 자격증명이라 실패하는 것이 정상이다. PAUSED라 스케쥴러가 건드리지 않는다(재개 금지)
CREATE TEMP TABLE seed_crypto (broker, ticker, currency_seed, daily_spend, base_price, inject_amount, rule_amount) ON COMMIT DROP AS
VALUES ('BINANCE', 'ETH', 1000::numeric, 5::numeric, 3000::numeric, 500::numeric, 5::numeric),
       ('UPBIT', 'BTC', 1500000::numeric, 7500::numeric, 95000000::numeric, 750000::numeric, 7500::numeric);

INSERT INTO trading.accounts (id, user_id, nickname, broker, account_no, app_key, secret_key)
SELECT md5('dev-seed:crypto:' || lower(k.broker) || ':account')::uuid, a.user_id,
       CASE k.broker WHEN 'BINANCE' THEN '[시드] 바이낸스' ELSE '[시드] 업비트' END, k.broker, a.account_no, a.app_key, a.secret_key
FROM seed_crypto k
CROSS JOIN LATERAL (SELECT * FROM trading.accounts
                    WHERE user_id = '00000000-0000-0000-0000-000000000001' AND broker = 'MOCK'
                      AND nickname LIKE '[시드]%' AND deleted_at IS NULL ORDER BY created_at LIMIT 1) a
ON CONFLICT (id) DO NOTHING;

INSERT INTO trading.strategy (id, account_id, type, ticker, status, cycle_seed_type)
SELECT md5('dev-seed:crypto:' || lower(k.broker) || ':strategy')::uuid, md5('dev-seed:crypto:' || lower(k.broker) || ':account')::uuid,
       'DCA', k.ticker, 'PAUSED', 'NONE'
FROM seed_crypto k WHERE EXISTS (SELECT 1 FROM trading.accounts WHERE id = md5('dev-seed:crypto:' || lower(k.broker) || ':account')::uuid)
ON CONFLICT (id) DO NOTHING;

INSERT INTO trading.strategy_version (id, strategy_id, version_no)
SELECT md5('dev-seed:crypto:' || lower(k.broker) || ':version')::uuid, s.id, 1
FROM seed_crypto k JOIN trading.strategy s ON s.id = md5('dev-seed:crypto:' || lower(k.broker) || ':strategy')::uuid
ON CONFLICT (id) DO NOTHING;

-- DCA 규칙: 매일 BUY 시장가 고정 금액, 부족 시 가용만큼(PARTIAL) — 코인 실행 시각 04시(KST)
INSERT INTO trading.strategy_dca_rule (id, strategy_version_id, rule_key, sort_order, direction, frequency, order_type,
                                       execution_hour, sizing_type, amount, shortfall_policy)
SELECT md5('dev-seed:crypto:' || lower(k.broker) || ':rule')::uuid, v.id,
       md5('dev-seed:crypto:' || lower(k.broker) || ':rulekey')::uuid::text, 0, 'BUY', 'DAILY', 'MARKET',
       4, 'AMOUNT', k.rule_amount, 'PARTIAL'
FROM seed_crypto k JOIN trading.strategy_version v ON v.id = md5('dev-seed:crypto:' || lower(k.broker) || ':version')::uuid
ON CONFLICT (id) DO NOTHING;

-- 진행 중 사이클: start_amount = open_amount + INJECT — CapitalLedgerService.inject가 start_amount += 예수금으로 갱신한다
INSERT INTO trading.strategy_cycle (id, strategy_id, strategy_version_id, start_amount, open_amount, start_date, created_at)
SELECT md5('dev-seed:crypto:' || lower(k.broker) || ':cycle')::uuid, v.strategy_id, v.id,
       k.currency_seed + k.inject_amount, k.currency_seed, d.start_date, d.start_date::timestamp AT TIME ZONE 'Asia/Seoul'
FROM seed_crypto k
JOIN trading.strategy_version v ON v.id = md5('dev-seed:crypto:' || lower(k.broker) || ':version')::uuid
CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE 'Asia/Seoul')::date - 120 AS start_date) d
ON CONFLICT (id) DO NOTHING;

-- 개장 스냅샷: 예수금 = 시드, 보유 0
INSERT INTO trading.cycle_position (id, strategy_cycle_id, deposit, closing_price, avg_price, holdings, created_at)
SELECT md5('dev-seed:crypto:' || lower(k.broker) || ':pos:open')::uuid, c.id, k.currency_seed, k.base_price, NULL, 0, c.created_at
FROM seed_crypto k JOIN trading.strategy_cycle c ON c.id = md5('dev-seed:crypto:' || lower(k.broker) || ':cycle')::uuid
ON CONFLICT (id) DO NOTHING;

-- 일별 스냅샷(주말 포함): 누적 매수 = 일수 × 일 매수액, deposit = 시드 − 누적 매수 (+ INJECT 이후 주입액), avg = 누적 매입원가 ÷ holdings
CREATE TEMP TABLE seed_crypto_positions ON COMMIT DROP AS
WITH days AS (
    SELECT k.broker, k.currency_seed, k.daily_spend, k.inject_amount, c.id AS cycle_id, c.start_date, d::date AS day,
           round((k.base_price * (1 + 0.12 * sin(extract(epoch FROM d) / 86400 / 11) + (d::date - c.start_date) * 0.0004))::numeric, 2) AS closing_price
    FROM seed_crypto k
    JOIN trading.strategy_cycle c ON c.id = md5('dev-seed:crypto:' || lower(k.broker) || ':cycle')::uuid
    CROSS JOIN generate_series(c.start_date + 1, (now() AT TIME ZONE 'Asia/Seoul')::date - 1, interval '1 day') d
), cum AS (
    SELECT *, sum(daily_spend / closing_price) OVER (PARTITION BY broker ORDER BY day) AS holdings,
           sum(daily_spend) OVER (PARTITION BY broker ORDER BY day) AS spent
    FROM days
)
SELECT md5('dev-seed:crypto:' || lower(broker) || ':pos:' || day)::uuid AS id, cycle_id, broker,
       currency_seed - spent + CASE WHEN day >= start_date + 60 THEN inject_amount ELSE 0 END AS deposit,
       closing_price, round(spent / holdings, 2) AS avg_price, round(holdings, 8) AS holdings,
       (day + time '04:30') AT TIME ZONE 'Asia/Seoul' AS created_at, day
FROM cum;

INSERT INTO trading.cycle_position (id, strategy_cycle_id, deposit, closing_price, avg_price, holdings, created_at)
SELECT id, cycle_id, deposit, closing_price, avg_price, holdings, created_at FROM seed_crypto_positions
ON CONFLICT (id) DO NOTHING;

-- 60일 전 INJECT 이력(예수금만 주입 — 주식 매입원가 0): 그날 스냅샷에 연결
INSERT INTO trading.cycle_capital_event (id, strategy_cycle_id, cycle_position_id, kind, deposit_delta, holdings_delta,
                                         principal_delta, occurred_at)
SELECT md5('dev-seed:crypto:' || lower(k.broker) || ':inject')::uuid, p.cycle_id, p.id, 'INJECT', k.inject_amount, 0, k.inject_amount, p.created_at
FROM seed_crypto k
JOIN seed_crypto_positions p ON p.broker = k.broker AND p.day = (SELECT start_date + 60 FROM trading.strategy_cycle WHERE id = p.cycle_id)
ON CONFLICT (id) DO NOTHING;

-- 체결 주문: 최근 7일 중 3일(1·3·5일 전) 소수 수량 종결 BUY — 코인 DCA 주문 leg는 DCA_{rule_key}
INSERT INTO trading.orders (id, account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, order_leg,
                            price, quantity, status, external_order_id, filled_quantity, filled_price)
SELECT md5('dev-seed:crypto:order:' || lower(k.broker) || ':' || o.ago)::uuid,
       md5('dev-seed:crypto:' || lower(k.broker) || ':account')::uuid, c.id,
       (now() AT TIME ZONE 'Asia/Seoul')::date - o.ago, k.ticker, 'MARKET', 'AT_CLOSE', 'BUY',
       'DCA_' || md5('dev-seed:crypto:' || lower(k.broker) || ':rulekey')::uuid::text,
       k.base_price, 0.0123, 'FILLED', 'SEED-' || left(md5(k.broker || o.ago), 10), 0.0123, k.base_price
FROM seed_crypto k
JOIN trading.strategy_cycle c ON c.id = md5('dev-seed:crypto:' || lower(k.broker) || ':cycle')::uuid
CROSS JOIN (VALUES (1), (3), (5)) AS o(ago)
ON CONFLICT (id) DO NOTHING;

COMMIT;
