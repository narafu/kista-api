-- strategy_configs 테이블 제거: StrategyConfig record는 dead code로 삭제됨(a642368),
-- 전략 설정은 V11에서 분리한 strategies 테이블이 대체
DROP TABLE IF EXISTS strategy_configs;
