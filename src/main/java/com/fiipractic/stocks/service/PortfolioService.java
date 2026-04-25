package com.fiipractic.stocks.service;

import com.fiipractic.stocks.dto.BuyStockRequest;
import com.fiipractic.stocks.dto.CreatePortfolioRequest;
import com.fiipractic.stocks.dto.HoldingDTO;
import com.fiipractic.stocks.dto.PortfolioDTO;
import com.fiipractic.stocks.dto.PortfolioValuationDTO;
import com.fiipractic.stocks.dto.PositionSummaryDTO;
import com.fiipractic.stocks.exception.PortfolioNotFoundException;
import com.fiipractic.stocks.exception.UserNotOwnerOfPortfolioException;
import com.fiipractic.stocks.model.Portfolio;
import com.fiipractic.stocks.model.PortfolioHolding;
import com.fiipractic.stocks.model.Stock;
import com.fiipractic.stocks.repository.PortfolioHoldingRepository;
import com.fiipractic.stocks.repository.PortfolioRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final PortfolioRepository portfolioRepository;
    private final PortfolioHoldingRepository portfolioHoldingRepository;
    private final StockService stockService;
    private final PriceRefreshPublisher priceRefreshPublisher;

    public PortfolioService(PortfolioRepository portfolioRepository,
                            PortfolioHoldingRepository portfolioHoldingRepository,
                            StockService stockService,
                            PriceRefreshPublisher priceRefreshPublisher) {
        this.portfolioRepository = portfolioRepository;
        this.portfolioHoldingRepository = portfolioHoldingRepository;
        this.stockService = stockService;
        this.priceRefreshPublisher = priceRefreshPublisher;
    }

    @Transactional
    public PortfolioDTO createPortfolio(String userId, CreatePortfolioRequest request) {
        Portfolio portfolio = Portfolio.builder()
                .name(request.getName())
                .description(request.getDescription())
                .holdings(new ArrayList<>())
                .userId(userId)
                .build();
        Portfolio saved = portfolioRepository.save(portfolio);
        
        // Log portfolio creation to LogBull using MDC
        try {
            MDC.put("action", "portfolio_created");
            MDC.put("username", userId);
            MDC.put("portfolioId", String.valueOf(saved.getId()));
            MDC.put("portfolioName", saved.getName());
            log.info("Portfolio created: {}", saved.getName());
        } finally {
            MDC.clear();
        }
        
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<PortfolioDTO> getUserPortfolios(String userId) {
        return portfolioRepository.findByUserId(userId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PortfolioDTO> getAllPortfolios() {
        return portfolioRepository.findAllPortfolios()
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public PortfolioDTO buyStock(String userId, Long portfolioId, BuyStockRequest request) {
        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new UserNotOwnerOfPortfolioException("User is not owner of portfolio or portfolio does not exist"));

        // find existing stock by symbol, or create it if it doesn't exist yet
        Stock stock = stockService.findOrCreate(request.getSymbol());

        PortfolioHolding holding = PortfolioHolding.builder()
                .portfolio(portfolio)
                .stock(stock)
                .quantity(request.getQuantity())
                .purchasePrice(request.getPurchasePrice())
                .build();

        portfolioHoldingRepository.save(holding);
        portfolio.getHoldings().add(holding);

        return toDTO(portfolio);
    }

    @Transactional(readOnly = true)
    public Portfolio getPortfolioById(Long portfolioId) {
        return portfolioRepository.findById(portfolioId)
                .orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found: " + portfolioId));
    }

    @Transactional(readOnly = true)
    public PortfolioValuationDTO calculateValuation(String userId, Long portfolioId) {
        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new UserNotOwnerOfPortfolioException("Portfolio not found or access denied"));

        // group holdings by stock symbol
        Map<String, List<PortfolioHolding>> holdingsBySymbol = portfolio.getHoldings().stream()
                .collect(Collectors.groupingBy(h -> h.getStock().getSymbol()));

        List<PositionSummaryDTO> positions = new ArrayList<>();
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalCurrentValue = BigDecimal.ZERO;

        for (Map.Entry<String, List<PortfolioHolding>> entry : holdingsBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<PortfolioHolding> holdings = entry.getValue();

            // calculate totals for this symbol
            int totalQuantity = holdings.stream().mapToInt(PortfolioHolding::getQuantity).sum();

            // calculate weighted average purchase price
            BigDecimal totalCost = holdings.stream()
                    .map(h -> h.getPurchasePrice().multiply(BigDecimal.valueOf(h.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal avgPurchasePrice = totalCost.divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP);

            // get current price from Stock entity
            Stock stock = holdings.get(0).getStock();
            BigDecimal currentPrice = stock.getCurrentPrice();

            // calculate invested and current value
            BigDecimal invested = avgPurchasePrice.multiply(BigDecimal.valueOf(totalQuantity));
            BigDecimal currentValue = currentPrice != null
                    ? currentPrice.multiply(BigDecimal.valueOf(totalQuantity))
                    : invested; // fallback to purchase price if no current price

            BigDecimal profitLoss = currentValue.subtract(invested);
            BigDecimal profitLossPercent = invested.compareTo(BigDecimal.ZERO) > 0
                    ? profitLoss.divide(invested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            positions.add(new PositionSummaryDTO(
                    symbol,
                    totalQuantity,
                    avgPurchasePrice,
                    currentPrice,
                    invested,
                    currentValue,
                    profitLoss,
                    profitLossPercent
            ));

            totalInvested = totalInvested.add(invested);
            totalCurrentValue = totalCurrentValue.add(currentValue);
        }

        BigDecimal totalProfitLoss = totalCurrentValue.subtract(totalInvested);
        BigDecimal totalProfitLossPercent = totalInvested.compareTo(BigDecimal.ZERO) > 0
                ? totalProfitLoss.divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        return new PortfolioValuationDTO(
                portfolio.getId(),
                portfolio.getName(),
                totalInvested,
                totalCurrentValue,
                totalProfitLoss,
                totalProfitLossPercent,
                positions,
                LocalDateTime.now()
        );
    }

    public RefreshResponseDTO refreshPortfolioPrices(String userId, Long portfolioId, String correlationId) {
        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new UserNotOwnerOfPortfolioException("Portfolio not found or access denied"));

        List<String> symbols = portfolio.getHoldings().stream()
                .map(h -> h.getStock().getSymbol())
                .distinct()
                .toList();

        // queue refresh for each symbol with correlation ID
        symbols.forEach(symbol -> {
            String childCorrelationId = correlationId + "." + symbol;
            priceRefreshPublisher.publishRefresh(symbol, userId, childCorrelationId);
        });
        
        // Log portfolio refresh completion using MDC
        try {
            MDC.put("action", "portfolio_refresh_queued");
            MDC.put("portfolioId", String.valueOf(portfolioId));
            MDC.put("userId", userId);
            MDC.put("symbolCount", String.valueOf(symbols.size()));
            MDC.put("correlationId", correlationId);
            log.info("Queued {} price refreshes for portfolio: {}", symbols.size(), portfolio.getName());
        } finally {
            MDC.clear();
        }

        return new RefreshResponseDTO(
                portfolioId.toString(), symbols, symbols.size(), "Price refresh queued for " + symbols.size() + " stocks", correlationId);
    }

    private PortfolioDTO toDTO(Portfolio p) {
        PortfolioDTO dto = new PortfolioDTO();
        dto.setId(p.getId());
        dto.setName(p.getName());
        dto.setDescription(p.getDescription());
        dto.setCreatedAt(p.getCreatedAt());
        dto.setHoldings(p.getHoldings().stream().map(this::toHoldingDTO).collect(Collectors.toList()));
        return dto;
    }

    private HoldingDTO toHoldingDTO(PortfolioHolding h) {
        return new HoldingDTO(
                h.getId(),
                h.getStock().getSymbol(),
                h.getQuantity(),
                h.getPurchasePrice(),
                h.getPurchasedAt()
        );
    }

    public record RefreshResponseDTO(String portfolioId, List<String> symbolsQueued, int totalSymbols, String message, String correlationId) {
    }
}