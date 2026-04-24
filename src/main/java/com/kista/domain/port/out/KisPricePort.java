package com.kista.domain.port.out;

import java.math.BigDecimal;

public interface KisPricePort {
    BigDecimal getPrice(String token, String symbol);
}
