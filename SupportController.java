package com.zentramart.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

// Buyer/seller-facing support: file a complaint, see your complaints,
// and read your notifications (the bell in the header).
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SupportController {

    private final ComplaintRepository complaintRepository;
    private final NotificationRepository notificationRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;

    @Autowired
    public SupportController(ComplaintRepository complaintRepository, NotificationRepository notificationRepository,
                             OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             UserRepository userRepository) {
        this.complaintRepository = complaintRepository;
        this.notificationRepository = notificationRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
    }

    public static class ComplaintRequest {
        public Long raisedBy;
        public Long orderId;      // optional
        public String description;
    }

    // POST /api/complaints
    @PostMapping("/complaints")
    public ResponseEntity<?> fileComplaint(@RequestBody ComplaintRequest req) {
        if (req.raisedBy == null || !userRepository.existsById(req.raisedBy)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Please log in to report a problem.");
        }
        if (req.description == null || req.description.trim().length() < 10) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Please describe the problem (at least 10 characters).");
        }

        Complaint c = new Complaint();
        c.setRaisedBy(req.raisedBy);
        c.setDescription(req.description.trim());
        c.setStatus(Complaint.ComplaintStatus.OPEN);

        if (req.orderId != null) {
            Order order = orderRepository.findById(req.orderId).orElse(null);
            if (order == null || !order.getBuyerId().equals(req.raisedBy)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("That order doesn't belong to you.");
            }
            c.setOrderId(order.getOrderId());
            List<OrderItem> items = orderItemRepository.findByOrderId(order.getOrderId());
            if (!items.isEmpty()) c.setAgainstUserId(items.get(0).getSellerId());
        }

        Complaint saved = complaintRepository.save(c);

        Notification n = new Notification();
        n.setUserId(req.raisedBy);
        n.setType("COMPLAINT");
        n.setMessage("We received your report #" + saved.getComplaintId() + ". The admin team will review it.");
        notificationRepository.save(n);

        return ResponseEntity.ok(saved);
    }

    public static class ComplaintView {
        public Long complaintId;
        public Long orderId;
        public String description;
        public String status;
        public String resolutionNotes;
        public LocalDateTime createdAt;
        public LocalDateTime resolvedAt;
    }

    // GET /api/complaints/user/5
    @GetMapping("/complaints/user/{userId}")
    public List<ComplaintView> myComplaints(@PathVariable Long userId) {
        return complaintRepository.findAll().stream()
                .filter(c -> Objects.equals(c.getRaisedBy(), userId))
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .map(c -> {
                    ComplaintView v = new ComplaintView();
                    v.complaintId = c.getComplaintId();
                    v.orderId = c.getOrderId();
                    v.description = c.getDescription();
                    v.status = c.getStatus().name();
                    v.resolutionNotes = c.getResolutionNotes();
                    v.createdAt = c.getCreatedAt();
                    v.resolvedAt = c.getResolvedAt();
                    return v;
                })
                .collect(Collectors.toList());
    }

    // GET /api/notifications/5
    @GetMapping("/notifications/{userId}")
    public List<Notification> myNotifications(@PathVariable Long userId) {
        return notificationRepository.findByUserIdOrderBySentDateDesc(userId);
    }

    // PUT /api/notifications/5/read-all
    @PutMapping("/notifications/{userId}/read-all")
    public ResponseEntity<?> markAllRead(@PathVariable Long userId) {
        List<Notification> list = notificationRepository.findByUserIdOrderBySentDateDesc(userId);
        for (Notification n : list) {
            if (n.getIsRead() == null || !n.getIsRead()) {
                n.setIsRead(true);
                notificationRepository.save(n);
            }
        }
        return ResponseEntity.ok().build();
    }
}