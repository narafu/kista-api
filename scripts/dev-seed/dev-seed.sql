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
       LEAST(m.month + t.day_offset, (now() AT TIME ZONE 'Asia/Seoul')::date), t.amount + m.i * 1000, t.memo
FROM (SELECT i, (date_trunc('month', (now() AT TIME ZONE 'Asia/Seoul')::date) - make_interval(months => 11 - i))::date AS month
      FROM generate_series(0, 11) i) m
CROSS JOIN (VALUES ('111', 4, 4200000, '월급'), ('122', 5, 85000, '배당'), ('201', 6, 1100000, '관리비'),
                   ('202', 7, 640000, '장보기'), ('203', 8, 300000, NULL), ('301', 9, 500000, 'DCA 적립'),
                   ('302', 10, 100000, NULL))
    AS t(code, day_offset, amount, memo)
ON CONFLICT (id) DO NOTHING;

-- ── 가계부: 예산 — 생활비·주거비, 올해 1월부터 ──
INSERT INTO finance.finance_budgets (id, category_id, user_id, apply_start_date, amount)
SELECT md5('dev-seed:budget:' || b.code)::uuid, ('f1000000-0000-4000-8000-000000000' || b.code)::uuid,
       '00000000-0000-0000-0000-000000000001', date_trunc('year', (now() AT TIME ZONE 'Asia/Seoul')::date)::date, b.amount
FROM (VALUES ('202', 700000), ('201', 1000000)) AS b(code, amount)
ON CONFLICT (id) DO NOTHING;

-- ── 누적자산추이(equity-curve)용: KIS [시드] 전략(PAUSED)의 진행 중 사이클에 평일 일별 포지션 스냅샷 ──
-- equity-curve는 MOCK 계좌를 제외하므로 KIS 시드 사이클에 쌓는다. PAUSED라 스케쥴러가 건드리지 않는다
-- 스냅샷 시각은 04:30 KST 마감 배치와 동일, 보유 100주·평단 30 고정, 종가만 완만한 사인파로 변동
-- 전제: KIS [시드] 계좌·전략은 기존 로컬 DB에 있어야 한다(이 스크립트가 만들지 않음 — 자격증명이 가짜라 재개(resume) 금지)
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM trading.accounts WHERE user_id = '00000000-0000-0000-0000-000000000001'
                   AND broker = 'KIS' AND nickname LIKE '[시드]%' AND deleted_at IS NULL) THEN
        RAISE NOTICE 'KIS [시드] 계좌 없음 — equity-curve 포지션·관리자 주문 시드 생략';
    END IF;
END $$;

CREATE TEMP TABLE seed_positions ON COMMIT DROP AS
SELECT md5('dev-seed:pos:' || c.id || ':' || d::date)::uuid AS id, c.id AS cycle_id, s.type,
       p.usd_deposit,
       round((30 + 6 * sin(extract(epoch FROM d) / 86400 / 9) + (d::date - c.start_date) * 0.02)::numeric, 2) AS closing_price,
       (d::date + time '04:30') AT TIME ZONE 'Asia/Seoul' AS created_at
FROM trading.strategy_cycle c
JOIN trading.strategy s ON s.id = c.strategy_id
JOIN trading.accounts a ON a.id = s.account_id
JOIN LATERAL (SELECT usd_deposit FROM trading.cycle_position
              WHERE strategy_cycle_id = c.id AND deleted_at IS NULL ORDER BY created_at LIMIT 1) p ON true
CROSS JOIN generate_series(c.start_date + 1, (now() AT TIME ZONE 'Asia/Seoul')::date - 1, interval '1 day') d
WHERE a.user_id = '00000000-0000-0000-0000-000000000001' AND a.broker = 'KIS'
  AND a.nickname LIKE '[시드]%' AND s.status = 'PAUSED'
  AND c.end_date IS NULL AND c.deleted_at IS NULL AND s.deleted_at IS NULL AND a.deleted_at IS NULL
  AND extract(isodow FROM d) < 6;

INSERT INTO trading.cycle_position (id, strategy_cycle_id, usd_deposit, closing_price, avg_price, holdings, created_at)
SELECT id, cycle_id, usd_deposit, closing_price, 30.00, 100, created_at FROM seed_positions
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

COMMIT;
