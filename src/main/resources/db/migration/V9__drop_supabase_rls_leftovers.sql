-- Supabase → 자체호스팅 postgres 이관(2026-08-07) 시 딸려온 잔재 정리
-- rls_auto_enable(): 신규 public 테이블에 RLS를 자동 활성화하던 Supabase 이벤트 트리거 함수.
-- 이관 후 이벤트 트리거 자체는 등록되지 않은 상태(\dy 0 rows)로 죽어있었으나 함수 정의만 남아있었음
DROP FUNCTION IF EXISTS public.rls_auto_enable();

-- 위 함수(또는 Supabase 대시보드)로 인해 정책(Policy) 없이 RLS만 켜져 있던 전체 27개 테이블 정리.
-- 현재는 앱이 접속하는 kista 롤이 Superuser+BypassRLS라 영향이 없었지만,
-- 정책 없는 RLS ON은 향후 별도 read-only 롤 도입 시 전량 조회 거부로 이어질 수 있는 잠재 위험이라 함께 비활성화
ALTER TABLE public.accounts DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_runtime_settings DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_error_logs DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.broker_tokens DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.cycle_position DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.cycle_position_infinite DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.fcm_device_tokens DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.fear_greed_snapshots DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.flyway_schema_history DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.housing_benchmark_prices DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.market_index_prices DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.orders DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.privacy_trade_base_orders DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.privacy_trade_bases DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.refresh_tokens DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.scheduler_locks DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.strategy DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.strategy_cycle DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.strategy_cycle_vr DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.strategy_infinite_version DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.strategy_version DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.strategy_vr_version DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.us_market_holidays DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_notification_prefs DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_settings DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.users DISABLE ROW LEVEL SECURITY;
