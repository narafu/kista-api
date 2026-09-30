-- 매매 런타임 정책(증권사별 신규 등록 허용·전략 타입별 생성 정책) 저장 행 — root public.admin_runtime_settings의
-- brokers/strategies 섹션이 정책 집행 주체인 trading-core 소유로 이동했다. 단일 행(setting_key='trading-policy'), jsonb.
-- 배포 순서: kista-trading을 먼저(또는 동시에) — root가 먼저 새 버전이 되면 정책 PUT이 거절되고 조회는 기본값으로 내려간다
-- (TradingPolicyHttpAdapter 전환기 호환). 이 마이그레이션은 매매 시간대 밖에서 적용할 것(deploy-trading 가드와 동일).
CREATE TABLE trading.trading_runtime_settings (
    setting_key character varying(100) NOT NULL,
    setting_value jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY trading.trading_runtime_settings
    ADD CONSTRAINT trading_runtime_settings_pkey PRIMARY KEY (setting_key);

-- 기존 데이터 백필(constraints.md "복제본 필수 조건 (a)"): 운영자가 root 화면에서 바꿔 둔 brokers/strategies 값이 기본값으로
-- 조용히 풀리지 않도록 옛 root 행에서 두 섹션만 1회 복사한다. 다른 서비스 스키마 참조 금지 규칙의 예외 — 런타임 참조가 아니라
-- 소유권 이동 시점의 일회성 이관이며, root 테이블이 없는 fresh trading 단독 DB에서도 깨지지 않도록 존재 여부로 가드한다.
-- root가 이미 새 버전으로 저장해 두 키가 사라진 행은 건너뛴다(그 경우 root의 PUT이 이 행을 채운다). 행이 없으면 기본값이 적용된다.
DO $$
BEGIN
    IF to_regclass('public.admin_runtime_settings') IS NOT NULL THEN
        INSERT INTO trading.trading_runtime_settings (setting_key, setting_value)
        SELECT 'trading-policy',
               jsonb_build_object('brokers', setting_value -> 'brokers', 'strategies', setting_value -> 'strategies')
        FROM public.admin_runtime_settings
        WHERE setting_key = 'runtime'
          AND setting_value ? 'brokers'
          AND setting_value ? 'strategies'
        ON CONFLICT (setting_key) DO NOTHING;
    END IF;
END $$;
