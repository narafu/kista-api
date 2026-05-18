package com.kista.domain.port.in;

import com.kista.domain.model.Account;
import java.util.List;

public interface AdminListAccountsUseCase {
    List<Account> listAll();
}
