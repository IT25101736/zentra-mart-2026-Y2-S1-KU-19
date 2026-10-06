package com.zentramart.backend;

/** The fulfilment flow deliberately has no backwards transitions. */
public final class OrderStatusTransitions {
    private OrderStatusTransitions() { }

    public static boolean isAllowed(Order.OrderStatus current, Order.OrderStatus requested) {
        if (current == requested) return true; // makes a retried request safe
        return (current == Order.OrderStatus.PENDING && requested == Order.OrderStatus.SHIPPED)
                || (current == Order.OrderStatus.SHIPPED && requested == Order.OrderStatus.DELIVERED);
    }
}
