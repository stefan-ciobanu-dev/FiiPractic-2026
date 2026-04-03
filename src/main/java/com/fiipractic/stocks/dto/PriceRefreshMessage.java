package com.fiipractic.stocks.dto;

import java.time.LocalDateTime;

public record PriceRefreshMessage( String symbol, LocalDateTime requestedAt, String requestedBy,String correlationId) {
}
