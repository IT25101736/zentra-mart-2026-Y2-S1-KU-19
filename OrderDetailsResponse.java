package com.zentramart.backend;

import java.util.List;

/** Keeps the API response useful without making entity relationships eager. */
public record OrderDetailsResponse(Order order, List<OrderItem> items, List<OrderTracking> tracking) { }
