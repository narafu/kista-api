package com.kista.application.usecase;

public interface FetchHousingPriceIndexUseCase {
    // KB Land 주간 아파트 매매가격지수 데이터를 조회해 저장 — years: 조회 기간(년)
    void fetchAndSave(int years);
}
