package com.kista.api.market.application.port.output;

import com.kista.api.market.domain.model.FearGreedRating;

// alternative.me Crypto Fear & Greed Index API
public interface CryptoFearGreedPort {
    record CryptoFearGreedData(int value, FearGreedRating rating) {}
    CryptoFearGreedData fetch();
}
