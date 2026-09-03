package com.kista.application.usecase;

import com.kista.domain.model.backtest.BacktestCommand;
import com.kista.domain.model.backtest.BacktestResult;

// 과거 일봉 기반 전략 시뮬레이션 — 계좌·소유권과 무관(로그인만 필요)
public interface BacktestUseCase {
    BacktestResult run(BacktestCommand command);
}
