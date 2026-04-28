package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FidaOrderRequestDto;
import com.kista.adapter.in.web.dto.PortfolioSnapshotResponse;
import com.kista.adapter.in.web.dto.TradeHistoryResponse;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final GetTradeHistoryUseCase getTradeHistoryUseCase;
    private final GetPortfolioUseCase getPortfolioUseCase;
    private final ExecuteFidaOrderUseCase executeFidaOrderUseCase;

    public DashboardController(GetTradeHistoryUseCase getTradeHistoryUseCase,
                               GetPortfolioUseCase getPortfolioUseCase,
                               ExecuteFidaOrderUseCase executeFidaOrderUseCase) {
        this.getTradeHistoryUseCase = getTradeHistoryUseCase;
        this.getPortfolioUseCase = getPortfolioUseCase;
        this.executeFidaOrderUseCase = executeFidaOrderUseCase;
    }

    @GetMapping("/trades")
    public List<TradeHistoryResponse> getTrades(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "SOXL") String symbol) {
        LocalDate resolvedFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        return getTradeHistoryUseCase.getHistory(resolvedFrom, resolvedTo, symbol)
                .stream().map(TradeHistoryResponse::from).toList();
    }

    @GetMapping("/portfolio/current")
    public PortfolioSnapshotResponse getPortfolioCurrent() {
        return PortfolioSnapshotResponse.from(getPortfolioUseCase.getCurrent());
    }

    @GetMapping("/portfolio/snapshots")
    public List<PortfolioSnapshotResponse> getPortfolioSnapshots(
            @RequestParam(defaultValue = "30") int days) {
        return getPortfolioUseCase.getSnapshots(days)
                .stream().map(PortfolioSnapshotResponse::from).toList();
    }

    @PostMapping("/orders/fida")
    @ResponseStatus(HttpStatus.CREATED)
    public void placeFidaOrder(@RequestBody @Valid FidaOrderRequestDto dto) {
        executeFidaOrderUseCase.execute(new FidaOrderRequest(
                dto.symbol(), dto.direction(), dto.qty(), dto.price()));
    }
}
