package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;
import java.util.List;

public interface AdminListTradesUseCase {
    List<Order> listAll(); // 최근 30일 전체 계좌
}
