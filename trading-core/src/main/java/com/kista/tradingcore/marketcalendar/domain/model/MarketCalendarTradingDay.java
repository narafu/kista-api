package com.kista.tradingcore.marketcalendar.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;

// 원본 캘린더의 미국 거래일과 뉴욕 현지 시각 — KST 변환은 저장 어댑터에서만 수행
public record MarketCalendarTradingDay(LocalDate date, LocalTime open, LocalTime close) {}