package com.zentramart.backend.order.controller;

import com.zentramart.backend.account.model.User;
import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.admin.model.DeliveryPartner;
import com.zentramart.backend.admin.repository.DeliveryPartnerRepository;
import com.zentramart.backend.listing.model.Product;
import com.zentramart.backend.listing.repository.ProductRepository;
import com.zentramart.backend.order.model.Delivery;
import com.zentramart.backend.order.model.Notification;
import com.zentramart.backend.order.model.Order;
import com.zentramart.backend.order.model.OrderItem;
import com.zentramart.backend.order.repository.DeliveryRepository;
import com.zentramart.backend.order.repository.NotificationRepository;
import com.zentramart.backend.order.repository.OrderItemRepository;
import com.zentramart.backend.order.repository.OrderRepository;
import com.zentramart.backend.order.service.OrderEmailService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Delivery QR codes.
//  - Every order gets a secret code like "ZM-K7QM-4XPA".
//  - The buyer's My Orders page shows it as a QR code.
//  - The QR is a LINK to the courier page, e.g.
//      http://192.168.43.15:8080/app/courier-scan.html?order=8&code=ZM-K7QM-4XPA
//    so the driver scans it with their phone's normal camera app and the page opens
//    with the order already checked. They tap "Confirm delivery" to mark it delivered.
//  - The laptop's Wi-Fi address is found automatically. To use a tunnel or a
//    published site instead, set this in application.properties:
//      zentra.courier-page=https://your-address/app/courier-scan.html
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DeliveryVerifyController {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryPartnerRepository deliveryPartnerRepository;
    private final NotificationRepository notificationRepository;
    private final OrderEmailService orderEmailService;

    // Optional: full address of the courier page (tunnel / published site).
    // Leave it out to use this laptop's Wi-Fi address automatically.
    @Value("${zentra.courier-page:}")
    private String courierPage;

    @Value("${server.port:8080}")
    private int serverPort;

    // No 0/O or 1/I/L, so codes are easy to read and type
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public DeliveryVerifyController(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                                    ProductRepository productRepository, UserRepository userRepository,
                                    DeliveryRepository deliveryRepository, DeliveryPartnerRepository deliveryPartnerRepository,
                                    NotificationRepository notificationRepository, OrderEmailService orderEmailService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryPartnerRepository = deliveryPartnerRepository;
        this.notificationRepository = notificationRepository;
        this.orderEmailService = orderEmailService;
    }

    // ============================ helpers ============================

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
    }

    private String newCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder("ZM-");
            for (int i = 0; i < 8; i++) {
                if (i == 4) sb.append('-');
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            code = sb.toString();
        } while (orderRepository.existsByDeliveryCode(code));
        return code;
    }

    // Older orders were made before codes existed - give them one the first time it's needed
    private String ensureCode(Order order) {
        if (order.getDeliveryCode() == null || order.getDeliveryCode().isBlank()) {
            order.setDeliveryCode(newCode());
            orderRepository.save(order);
        }
        return order.getDeliveryCode();
    }

    // The link inside the QR code: the courier page + this order's number and code
    private String qrText(Order order) {
        return courierPageUrl() + "?order=" + order.getOrderId() + "&code=" + order.getDeliveryCode();
    }

    private String courierPageUrl() {
        if (courierPage != null && !courierPage.isBlank()) return courierPage.trim();
        String ip = wifiAddress();
        return "http://" + (ip != null ? ip : "localhost") + ":" + serverPort + "/app/courier-scan.html";
    }

    // Finds this laptop's address on the Wi-Fi / hotspot (like 192.168.43.15),
    // skipping virtual adapters (VirtualBox, VMware, Hyper-V, WSL, VPNs, Bluetooth).
    // Checked every time, so it follows you if you switch networks.
    static String wifiAddress() {
        String best = null;
        int bestScore = -1;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = (ni.getName() + " " + ni.getDisplayName()).toLowerCase();
                if (name.matches(".*(virtual|vmware|vbox|hyper-v|vethernet|wsl|docker|bluetooth|vpn|tap-|loopback).*")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    int score = 1;
                    if (name.matches(".*(wi-?fi|wireless|wlan|802\\.11).*")) score += 4;
                    if (ip.startsWith("192.168.") || ip.startsWith("172.20.10.")) score += 2;
                    if (score > bestScore) {
                        bestScore = score;
                        best = ip;
                    }
                }
            }
        } catch (Exception e) {
            // no network information - fall back to localhost
        }
        return best;
    }

    // The value after "name=" in a link like "...?order=8&code=ZM-K7QM-4XPA" (or null)
    private static String linkParam(String input, String name) {
        String upper = input.toUpperCase();
        int at = upper.indexOf(name.toUpperCase() + "=");
        if (at < 0) return null;
        String value = input.substring(at + name.length() + 1);
        int amp = value.indexOf('&');
        if (amp >= 0) value = value.substring(0, amp);
        return URLDecoder.decode(value, StandardCharsets.UTF_8).trim();
    }

    // Accepts the QR link ("http://.../courier-scan.html?order=8&code=ZM-K7QM-4XPA"),
    // the old QR text ("ZENTRA-DELIVERY|8|ZM-K7QM-4XPA") or a typed code
    // ("zm-k7qm-4xpa", "K7QM4XPA", ...) and turns it into "ZM-K7QM-4XPA".
    static String normalizeCode(String input) {
        if (input == null) return null;
        String fromLink = linkParam(input, "code");
        String s = (fromLink != null ? fromLink : input).trim().toUpperCase();
        if (s.contains("|")) s = s.substring(s.lastIndexOf('|') + 1);
        String chars = s.replaceAll("[^A-Z0-9]", "");
        if (chars.startsWith("ZM") && chars.length() == 10) chars = chars.substring(2);
        if (chars.length() != 8) return null;
        return "ZM-" + chars.substring(0, 4) + "-" + chars.substring(4);
    }

    // The order number inside the QR, if there is one (used as an extra check)
    static Long orderIdInQr(String input) {
        if (input == null) return null;
        String fromLink = linkParam(input, "order");
        if (fromLink != null) {
            try {
                return Long.parseLong(fromLink);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!input.contains("|")) return null;
        String[] parts = input.trim().split("\\|");
        if (parts.length != 3) return null;
        try {
            return Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void notifyUser(Long userId, String message) {
        if (userId == null) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType("DELIVERY");
        n.setMessage(message);
        notificationRepository.save(n);
    }

    public static class ItemLine {
        public String title;
        public Integer quantity;
    }

    public static class VerifyView {
        public boolean match = true;
        public Long orderId;
        public String orderStatus;
        public String buyerName;
        public String shippingAddress;
        public BigDecimal totalAmount;
        public List<ItemLine> items = new ArrayList<>();
        public int itemCount;
        public String deliveryStatus;   // null if the admin hasn't created a delivery yet
        public String courierName;
        public LocalDateTime deliveredAt;
        public boolean canConfirm;      // true when the courier may mark it delivered now
        public String note;             // why it can't be confirmed (if it can't)
    }

    private VerifyView buildView(Order order) {
        VerifyView v = new VerifyView();
        v.orderId = order.getOrderId();
        v.orderStatus = order.getOrderStatus().name();
        v.shippingAddress = order.getShippingAddress();
        v.totalAmount = order.getTotalAmount();
        v.buyerName = userRepository.findById(order.getBuyerId()).map(User::getFullName).orElse("Zentra Mart customer");

        for (OrderItem oi : orderItemRepository.findByOrderId(order.getOrderId())) {
            ItemLine line = new ItemLine();
            line.quantity = oi.getQuantity();
            line.title = productRepository.findById(oi.getProductId()).map(Product::getTitle).orElse("Product");
            v.items.add(line);
            v.itemCount += oi.getQuantity() == null ? 0 : oi.getQuantity();
        }

        Delivery d = deliveryRepository.findByOrderId(order.getOrderId()).orElse(null);
        if (d != null) {
            v.deliveryStatus = d.getStatus().name();
            v.deliveredAt = d.getDeliveryTime();
            if (d.getPartnerId() != null) {
                v.courierName = deliveryPartnerRepository.findById(d.getPartnerId()).map(DeliveryPartner::getName).orElse(null);
            }
        }

        if (order.getOrderStatus() == Order.OrderStatus.CANCELLED) {
            v.note = "This order was cancelled. Do not hand it over.";
        } else if (order.getOrderStatus() == Order.OrderStatus.DELIVERED) {
            v.note = "This order has already been delivered.";
        } else if (d == null || d.getPartnerId() == null) {
            v.note = "No courier has been assigned to this order yet. Ask the admin to assign one first.";
        } else {
            v.canConfirm = true;
        }
        return v;
    }

    // ============================ endpoints ============================

    // GET /api/orders/8/delivery-code?buyerId=5
    // The buyer's My Orders page calls this to draw the QR code.
    @GetMapping("/orders/{orderId}/delivery-code")
    public ResponseEntity<?> getDeliveryCode(@PathVariable Long orderId, @RequestParam Long buyerId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return ResponseEntity.notFound().build();
        if (!order.getBuyerId().equals(buyerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("This isn't your order.");
        }
        ensureCode(order);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", order.getOrderId());
        body.put("code", order.getDeliveryCode());
        body.put("qrText", qrText(order));      // the link the courier's phone opens
        return ResponseEntity.ok(body);
    }

    // GET /api/deliveries/verify?code=ZM-K7QM-4XPA   (or the QR link, or ZENTRA-DELIVERY|8|ZM-K7QM-4XPA)
    // The courier scanner calls this after reading the QR.
    @GetMapping("/deliveries/verify")
    public ResponseEntity<?> verify(@RequestParam String code) {
        String normalized = normalizeCode(code);
        if (normalized == null) return bad("That isn't a Zentra Mart delivery code.");
        Order order = orderRepository.findByDeliveryCode(normalized).orElse(null);
        Long qrOrderId = orderIdInQr(code);
        if (order == null || (qrOrderId != null && !qrOrderId.equals(order.getOrderId()))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No order matches this code. Do NOT hand over the parcel.");
        }
        return ResponseEntity.ok(buildView(order));
    }

    public static class ConfirmRequest {
        public String code;
    }

    // POST /api/deliveries/verify/confirm   body: { "code": "..." }
    // The courier taps "Confirm delivery" after handing the parcel over.
    // Marks the delivery + order as DELIVERED, notifies everyone and emails the buyer.
    @PostMapping("/deliveries/verify/confirm")
    public ResponseEntity<?> confirm(@RequestBody ConfirmRequest req) {
        String normalized = normalizeCode(req == null ? null : req.code);
        if (normalized == null) return bad("That isn't a Zentra Mart delivery code.");
        Order order = orderRepository.findByDeliveryCode(normalized).orElse(null);
        Long qrOrderId = orderIdInQr(req.code);
        if (order == null || (qrOrderId != null && !qrOrderId.equals(order.getOrderId()))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No order matches this code.");
        }

        VerifyView check = buildView(order);
        if (!check.canConfirm) return bad(check.note);

        Delivery d = deliveryRepository.findByOrderId(order.getOrderId()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (d.getPickupTime() == null) d.setPickupTime(now);
        d.setDeliveryTime(now);
        d.setStatus(Delivery.DeliveryStatus.DELIVERED);
        deliveryRepository.save(d);

        order.setOrderStatus(Order.OrderStatus.DELIVERED);
        orderRepository.save(order);

        String courier = check.courierName == null ? "the courier" : check.courierName;
        notifyUser(order.getBuyerId(), "Your order #" + order.getOrderId() + " was delivered by " + courier
                + " (confirmed by QR scan). Enjoy!");
        Set<Long> sellerIds = new LinkedHashSet<>();
        for (OrderItem oi : orderItemRepository.findByOrderId(order.getOrderId())) sellerIds.add(oi.getSellerId());
        for (Long sellerId : sellerIds) {
            notifyUser(sellerId, "Order #" + order.getOrderId() + " was delivered to the buyer (confirmed by QR scan).");
        }
        orderEmailService.orderStatusChanged(order.getOrderId(), Order.OrderStatus.DELIVERED);

        return ResponseEntity.ok(buildView(order));
    }
}