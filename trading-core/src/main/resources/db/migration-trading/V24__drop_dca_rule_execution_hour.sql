-- 15 T6: 코인 MARKET 주문 전용이던 실행 시각 컬럼을 지운다. 엔티티 매핑은 1f360bf50(운영 이미지 ca7709d26에 포함)에서 이미 사라졌다
-- 운영 확인(2026-11-20): strategy_dca_rule 11행 중 execution_hour 값 0행. 같은 이름 컬럼은 trading 스키마에 이 테이블 하나뿐이다
ALTER TABLE trading.strategy_dca_rule DROP COLUMN execution_hour;
