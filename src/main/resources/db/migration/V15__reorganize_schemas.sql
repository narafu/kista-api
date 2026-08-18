-- 논리적 도메인별 스키마 분리: kista(핵심 매매) / finance(가계부) / reference(외부 참조·시장 데이터)
-- flyway_schema_history는 public에 유지 (spring.flyway.default-schema=public)
-- 애플리케이션은 search_path로 스키마를 넘나들며 unqualified 이름을 그대로 사용 (Entity만 schema= 명시)

CREATE SCHEMA IF NOT EXISTS kista;
CREATE SCHEMA IF NOT EXISTS finance;
CREATE SCHEMA IF NOT EXISTS reference;

-- finance: 가계부 도메인
ALTER TABLE public.finance_accounts SET SCHEMA finance;
ALTER TABLE public.finance_asset_snapshots SET SCHEMA finance;
ALTER TABLE public.finance_budgets SET SCHEMA finance;
ALTER TABLE public.finance_categories SET SCHEMA finance;
ALTER TABLE public.finance_group_invitations SET SCHEMA finance;
ALTER TABLE public.finance_group_members SET SCHEMA finance;
ALTER TABLE public.finance_groups SET SCHEMA finance;
ALTER TABLE public.finance_monthly_closings SET SCHEMA finance;
ALTER TABLE public.finance_transactions SET SCHEMA finance;

-- reference: 외부 참조·시장 데이터 (PRIVACY 기준 매매표 포함, 전역 공유 데이터)
ALTER TABLE public.us_market_holidays SET SCHEMA reference;
ALTER TABLE public.housing_benchmark_prices SET SCHEMA reference;
ALTER TABLE public.housing_price_indices SET SCHEMA reference;
ALTER TABLE public.market_index_prices SET SCHEMA reference;
ALTER TABLE public.fear_greed_snapshots SET SCHEMA reference;
ALTER TABLE public.privacy_trade_bases SET SCHEMA reference;
ALTER TABLE public.privacy_trade_base_orders SET SCHEMA reference;

-- kista: 순수 매매 도메인 (계좌·주문·전략·포지션) — 인증/관리자/로그/알림 성격 테이블은 public 유지
ALTER TABLE public.accounts SET SCHEMA kista;
ALTER TABLE public.cycle_position SET SCHEMA kista;
ALTER TABLE public.cycle_position_infinite SET SCHEMA kista;
ALTER TABLE public.orders SET SCHEMA kista;
ALTER TABLE public.strategy SET SCHEMA kista;
ALTER TABLE public.strategy_cycle SET SCHEMA kista;
ALTER TABLE public.strategy_cycle_vr SET SCHEMA kista;
ALTER TABLE public.strategy_infinite_version SET SCHEMA kista;
ALTER TABLE public.strategy_version SET SCHEMA kista;
ALTER TABLE public.strategy_vr_version SET SCHEMA kista;

-- public 유지: users, user_settings, user_notification_prefs, refresh_tokens, broker_tokens,
-- admin_runtime_settings, audit_logs, app_error_logs, fcm_device_tokens, scheduler_locks

-- V14 주석("운영 안정화 1~2개월 후 별도 마이그레이션에서 DROP")의 유예기간보다 앞당겨 DROP (의도적 결정)
DROP TABLE IF EXISTS public.asset_monthly_checks_v12_backup;
DROP TABLE IF EXISTS public.assets_v12_backup;

-- unqualified 테이블명이 스키마를 순서대로 탐색하도록 DB 유저 기본 search_path 설정
ALTER ROLE kista SET search_path = kista, finance, reference, public;
