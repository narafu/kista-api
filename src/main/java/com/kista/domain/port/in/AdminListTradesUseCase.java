package com.kista.domain.port.in;

import com.kista.domain.model.order.TradeHistory;
import java.util.List;

public interface AdminListTradesUseCase {
    List<TradeHistory> listAll(); // 최근 30일 전체 계좌
}
