package com.zentramart.backend;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_tracking")
public class OrderTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tracking_id")
    private Long trackingId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Order.OrderStatus status;

    @Column(name = "location")
    private String location;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @PrePersist
    void assignEventTime() {
        if (eventTime == null) eventTime = LocalDateTime.now();
    }

    public OrderTracking() { }

    public Long getTrackingId() { return trackingId; }
    public void setTrackingId(Long trackingId) { this.trackingId = trackingId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Order.OrderStatus getStatus() { return status; }
    public void setStatus(Order.OrderStatus status) { this.status = status; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
}
