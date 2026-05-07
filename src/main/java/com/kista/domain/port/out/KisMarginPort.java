package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.MarginItem;

import java.util.List;

public interface KisMarginPort {
    // USD·KRW 두 통화의 증거금 정보 반환 (TTTC2101R)
    List<MarginItem> getMargin(Account account);
}
