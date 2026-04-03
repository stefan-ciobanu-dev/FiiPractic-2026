package com.fiipractic.stocks.consumer;

import com.fiipractic.stocks.config.RabbitMQConfig;
import com.fiipractic.stocks.dto.PriceRefreshMessage;
import com.fiipractic.stocks.model.Stock;
import com.fiipractic.stocks.repository.StockRepository;
import com.fiipractic.stocks.service.AlphaVantageClient;

import com.rabbitmq.client.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
public class PriceRefreshConsumer {

    private static final Logger log = LoggerFactory.getLogger(PriceRefreshConsumer.class);
    private static final long SLOW_API_THRESHOLD_MS = 2000;

    private final AlphaVantageClient alphaVantageClient;
    private final StockRepository stockRepository;

    public PriceRefreshConsumer(AlphaVantageClient alphaVantageClient, StockRepository stockRepository) {
        this.alphaVantageClient = alphaVantageClient;
        this.stockRepository = stockRepository;
    }
    /**
     * Queue listener to process incoming refresh messages.
     * This prevents exceeding Alpha Vantage's rate limit.
     * @apiNote {@code concurrency = "1"} ensures only one message is processed at a time to follow
     * Alpha Vantage's free tier limit of 1 concurrent request
     */
    @RabbitListener(
            queues = RabbitMQConfig.PRICE_REFRESH_QUEUE,
            concurrency = "1",
            ackMode = "MANUAL"
    )
    public void onPriceRefreshRequest(
            PriceRefreshMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        String correlationId = message.correlationId();

        log.info("[CONSUMER] Processing [{}] requested by [{}] - correlationId: {} - thread: {}",
                message.symbol(), message.requestedBy(), correlationId,
                Thread.currentThread().getName());

        // Log consumer processing start using MDC
        try {
            MDC.put("action", "price_processing_started");
            MDC.put("symbol", message.symbol());
            MDC.put("requestedBy", message.requestedBy());
            MDC.put("correlationId", correlationId);
            log.info("Started processing price refresh for {}", message.symbol());
        } finally {
            MDC.clear();
        }

        long startTime = System.currentTimeMillis();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        try {
            // fetch latest price from Alpha Vantage
            BigDecimal price = alphaVantageClient.fetchLatestPrice(message.symbol());

            long durationMs = System.currentTimeMillis() - startTime;

            // update the stock entity with current price
            Stock stock = stockRepository.findBySymbol(message.symbol())
                    .orElseThrow(() -> new RuntimeException("Stock not found: " + message.symbol()));

            stock.setCurrentPrice(price);
            stock.setLastPriceUpdate(LocalDateTime.now());
            stockRepository.save(stock);

            log.info("[CONSUMER] Updated price for [{}] to ${} at {} - correlationId: {}",
                    message.symbol(), price, stock.getLastPriceUpdate(), correlationId);

            // Log success to LogBull using MDC
            try {
                MDC.put("action", "price_stored");
                MDC.put("symbol", message.symbol());
                MDC.put("price", price.toString());
                MDC.put("durationMs", String.valueOf(durationMs));
                MDC.put("requestedBy", message.requestedBy());
                MDC.put("correlationId", correlationId);
                log.info("Price updated for {}: ${}", message.symbol(), price);
            } finally {
                MDC.clear();
            }

            // Warn if API call was slow
            if (durationMs > SLOW_API_THRESHOLD_MS) {
                try {
                    MDC.put("action", "slow_api_call");
                    MDC.put("symbol", message.symbol());
                    MDC.put("durationMs", String.valueOf(durationMs));
                    MDC.put("thresholdMs", String.valueOf(SLOW_API_THRESHOLD_MS));
                    MDC.put("correlationId", correlationId);
                    log.warn("Slow API call for {} took {}ms", message.symbol(), durationMs);
                } finally {
                    MDC.clear();
                }
            }

            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;

            log.error("[CONSUMER] Failed to fetch price for [{}]: {} - correlationId: {}",
                    message.symbol(), e.getMessage(), correlationId);

            // Log failure to LogBull using MDC
            try {
                MDC.put("action", "price_fetch_failed");
                MDC.put("symbol", message.symbol());
                MDC.put("error", e.getMessage());
                MDC.put("errorType", e.getClass().getSimpleName());
                MDC.put("durationMs", String.valueOf(durationMs));
                MDC.put("requestedBy", message.requestedBy());
                MDC.put("correlationId", correlationId);
                log.error("Failed to fetch price for {}", message.symbol(), e);
            } finally {
                MDC.clear();
            }

            channel.basicNack(deliveryTag, false, false);
        }
    }
}