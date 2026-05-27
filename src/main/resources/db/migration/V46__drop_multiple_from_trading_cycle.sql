-- multiple은 PRIVACY 전략에서 initialUsdDeposit ÷ currentCycleStart 로 동적 산출하므로 컬럼 불필요
ALTER TABLE trading_cycle DROP COLUMN multiple;
