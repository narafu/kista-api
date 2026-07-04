package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;

// Toss API {"result": ...} 래퍼 — 개별 래퍼 record 통합
record TossResult<T>(@JsonProperty("result") T result) {}
