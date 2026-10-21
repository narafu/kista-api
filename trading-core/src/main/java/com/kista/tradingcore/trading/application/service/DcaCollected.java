package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import com.kista.tradingcore.trading.domain.model.Order;

import java.util.Map;

// DCA 입력 수집 결과 — inputs: 커널 plan() 입력, recoveries: ruleKey → 이력 없이 남은 당일 규칙 주문(배치가 ORDERED로 복구 기록)
// 커널이 trading Order를 모르게 복구 대상은 PlanContext 밖으로 분리
record DcaCollected(CycleOrderStrategy.PlanContext.DcaPlanInputs inputs, Map<String, Order> recoveries) {}
