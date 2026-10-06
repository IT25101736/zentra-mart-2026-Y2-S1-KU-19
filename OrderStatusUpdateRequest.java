package com.zentramart.backend;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class OrderStatusUpdateRequest {
    @NotNull(message = "Order status is required.")
    private Order.OrderStatus status;

    @Size(max = 100)
    private String trackingNumber;

    @Size(max = 500)
    private String trackingUrl;

    @Size(max = 150)
    private String location;

    @Size(max = 1000)
    private String note;

    public Order.OrderStatus getStatus() { return status; }
    public void setStatus(Order.OrderStatus status) { this.status = status; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public String getTrackingUrl() { return trackingUrl; }
    public void setTrackingUrl(String trackingUrl) { this.trackingUrl = trackingUrl; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
