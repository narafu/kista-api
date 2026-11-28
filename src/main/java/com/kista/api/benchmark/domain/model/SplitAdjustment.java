package com.kista.api.benchmark.domain.model;

import java.time.LocalDate;

// 저장된 종가를 새 분할 기준으로 맞추는 조정 — through 이하 행의 종가에 numerator/denominator를 곱한다
public record SplitAdjustment(LocalDate through, int numerator, int denominator) {}
