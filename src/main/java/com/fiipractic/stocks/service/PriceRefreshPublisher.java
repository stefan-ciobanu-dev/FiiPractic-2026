package com.fiipractic.stocks.service;

import com.fiipractic.stocks.config.RabbitMQConfig;
import com.fiipractic.stocks.dto.PriceRefreshMessage;
import com.fiipractic.stocks.dto.StockDTO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PriceRefreshPublisher {

    private static final Logger log = LoggerFactory.getLogger(PriceRefreshPublisher.class);

    private final StockService stockService;
    private final RabbitTemplate rabbitTemplate;

    public PriceRefreshPublisher(StockService stockService, RabbitTemplate rabbitTemplate) {
        this.stockService = stockService;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishRefresh(String symbol, String requestedBy, String correlationId) {
        PriceRefreshMessage message = new PriceRefreshMessage(
                symbol.toUpperCase(),
                LocalDateTime.now(),
                requestedBy,
                correlationId
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.PRICE_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY,
                message
        );

        log.info("[PRODUCER] Queued price refresh for [{}] by user [{}] - correlationId: {}", 
                symbol, requestedBy, correlationId);
        
        // Log to LogBull using MDC
        try {
            MDC.put("action", "price_queued");
            MDC.put("symbol", symbol.toUpperCase());
            MDC.put("requestedBy", requestedBy);
            MDC.put("correlationId", correlationId);
            log.info("Price refresh queued for {}", symbol.toUpperCase());
        } finally {
            MDC.clear();
        }
    }

    public void publishRefreshAll(String requestedBy, String correlationId) {
        log.info("[PRODUCER] Queued price refresh for ALL stocks by user [{}] - correlationId: {}", 
                requestedBy, correlationId);

        List<StockDTO> stocks = stockService.getAllStocks();

        for (var stock : stocks) {
            // Generate child correlation ID for each stock (parent.child pattern)
            String childCorrelationId = correlationId + "." + UUID.randomUUID().toString().substring(0, 8);
            
            PriceRefreshMessage message = new PriceRefreshMessage(
                    stock.symbol(),
                    LocalDateTime.now(),
                    requestedBy,
                    childCorrelationId
            );

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.PRICE_EXCHANGE,
                    RabbitMQConfig.ROUTING_KEY,
                    message
            );

            log.info("[PRODUCER] Queued price refresh for [{}] by user [{}] - correlationId: {}", 
                    stock.symbol(), requestedBy, childCorrelationId);
        }
        
        // Log batch completion using MDC
        try {
            MDC.put("action", "batch_queued");
            MDC.put("requestedBy", requestedBy);
            MDC.put("correlationId", correlationId);
            MDC.put("stockCount", String.valueOf(stocks.size()));
            log.info("Queued {} price refresh requests", stocks.size());
        } finally {
            MDC.clear();
        }
    }
}
