package com.zentramart.backend.order.service;

import com.zentramart.backend.account.model.User;
import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.admin.model.DeliveryPartner;
import com.zentramart.backend.admin.repository.DeliveryPartnerRepository;
import com.zentramart.backend.common.service.EmailService;
import com.zentramart.backend.listing.model.Product;
import com.zentramart.backend.listing.repository.ProductRepository;
import com.zentramart.backend.order.model.Delivery;
import com.zentramart.backend.order.model.Order;
import com.zentramart.backend.order.model.OrderItem;
import com.zentramart.backend.order.repository.DeliveryRepository;
import com.zentramart.backend.order.repository.OrderItemRepository;
import com.zentramart.backend.order.repository.OrderRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Builds and sends the order emails:
//   - order confirmation (receipt) to the buyer
//   - "new order" email to each seller in the order
//   - shipped / delivered / cancelled updates to the buyer
// Emails go out in the background, so a slow Gmail never slows checkout down.
// If anything goes wrong here, it's only logged - it never breaks the order.
@Service
public class OrderEmailService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryPartnerRepository deliveryPartnerRepository;
    private final EmailService emailService;

    // Optional: where the site is hosted, e.g. http://localhost:5500
    // If set, emails get a "View my order" button. Leave blank while you open files directly.
    @Value("${zentra.frontend-url:}")
    private String frontendUrl;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a");
    private static final String ACCENT = "#D6273C";

    public OrderEmailService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             ProductRepository productRepository, UserRepository userRepository,
                             DeliveryRepository deliveryRepository, DeliveryPartnerRepository deliveryPartnerRepository,
                             EmailService emailService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryPartnerRepository = deliveryPartnerRepository;
        this.emailService = emailService;
    }

    // ======================= public methods (called by the controllers) =======================

    // Call right after checkout saves the order
    public void orderPlaced(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) return;
            List<Line> lines = loadLines(orderId);

            // 1) Receipt to the buyer
            User buyer = activeUser(order.getBuyerId());
            if (buyer != null) {
                String first = firstName(buyer.getFullName());
                StringBuilder body = new StringBuilder();
                body.append(paragraph("Hi " + esc(first) + ", thanks for shopping with Zentra Mart! "
                        + "We've received your order and passed it on to the seller."));
                body.append(itemsTable(lines));
                body.append(totalsTable(order, lines));
                body.append(infoBox("Shipping to", esc(order.getShippingAddress())));
                body.append(paragraph("We'll email you again when your order ships."));
                body.append(button("View my order", "orders.html"));

                String html = layout("Order confirmed", "Order #" + orderId + " \u00B7 " + dateOf(order), body.toString());
                String text = "Hi " + first + ",\n\nThanks for your order #" + orderId + "!\n\n"
                        + linesText(lines) + "\n" + totalsText(order, lines)
                        + "\nShipping to: " + order.getShippingAddress()
                        + "\n\nWe'll email you again when your order ships.\n\n- Zentra Mart";
                emailService.sendHtmlLater(buyer.getEmail(), "Order confirmed - #" + orderId, html, text);
            }

            // 2) One email per seller, listing only their items
            Map<Long, List<Line>> bySeller = new LinkedHashMap<>();
            for (Line l : lines) bySeller.computeIfAbsent(l.sellerId, k -> new ArrayList<>()).add(l);

            for (Map.Entry<Long, List<Line>> entry : bySeller.entrySet()) {
                User seller = activeUser(entry.getKey());
                if (seller == null) continue;
                List<Line> mine = entry.getValue();
                BigDecimal sellerTotal = BigDecimal.ZERO;
                for (Line l : mine) sellerTotal = sellerTotal.add(l.lineTotal);

                StringBuilder body = new StringBuilder();
                body.append(paragraph("Hi " + esc(firstName(seller.getFullName()))
                        + ", you have a new order. Please get these items ready - a courier will be assigned soon."));
                body.append(itemsTable(mine));
                body.append(simpleTotal("Your items total", sellerTotal));
                body.append(infoBox("Buyer", esc(buyer == null ? "Zentra Mart customer" : buyer.getFullName())
                        + "<br>" + esc(order.getShippingAddress())));
                body.append(button("Open seller dashboard", "seller-dashboard.html"));

                String html = layout("You have a new order", "Order #" + orderId + " \u00B7 " + dateOf(order), body.toString());
                String text = "Hi " + firstName(seller.getFullName()) + ",\n\nNew order #" + orderId + ":\n\n"
                        + linesText(mine) + "\nYour items total: " + money(sellerTotal)
                        + "\nShip to: " + order.getShippingAddress() + "\n\n- Zentra Mart";
                emailService.sendHtmlLater(seller.getEmail(), "New order #" + orderId + " - please prepare it", html, text);
            }
        } catch (Exception e) {
            System.out.println("[OrderEmailService] Could not prepare order emails for #" + orderId + ": " + e.getMessage());
        }
    }

    // Call whenever an order's status really changes (SHIPPED, DELIVERED or CANCELLED)
    public void orderStatusChanged(Long orderId, Order.OrderStatus newStatus) {
        try {
            if (newStatus == null || newStatus == Order.OrderStatus.PENDING) return;
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) return;
            User buyer = activeUser(order.getBuyerId());
            if (buyer == null) return;

            List<Line> lines = loadLines(orderId);
            String first = firstName(buyer.getFullName());
            String courierHtml = null;
            String courierText = null;

            Delivery d = deliveryRepository.findByOrderId(orderId).orElse(null);
            if (d != null && d.getPartnerId() != null) {
                DeliveryPartner p = deliveryPartnerRepository.findById(d.getPartnerId()).orElse(null);
                if (p != null) {
                    boolean hasVehicle = p.getVehicleType() != null && !p.getVehicleType().isBlank();
                    courierHtml = "<strong>" + esc(p.getName()) + "</strong><br>" + esc(p.getContactNo())
                            + (hasVehicle ? " &middot; " + esc(p.getVehicleType()) : "");
                    courierText = p.getName() + ", " + p.getContactNo() + (hasVehicle ? ", " + p.getVehicleType() : "");
                }
            }

            String subject, title, intro, introText;
            StringBuilder body = new StringBuilder();

            switch (newStatus) {
                case SHIPPED:
                    subject = "Your order #" + orderId + " has shipped";
                    title = "Your order is on the way";
                    intro = "Good news, " + esc(first) + "! Your order has left the seller and is on its way to you.";
                    introText = "Good news! Your order #" + orderId + " has shipped and is on its way.";
                    body.append(paragraph(intro));
                    if (courierHtml != null) body.append(infoBox("Your courier", courierHtml));
                    body.append(itemsTable(lines));
                    body.append(infoBox("Delivering to", esc(order.getShippingAddress())));
                    body.append(button("Track my order", "orders.html"));
                    break;
                case DELIVERED:
                    subject = "Your order #" + orderId + " was delivered";
                    title = "Delivered!";
                    intro = "Hi " + esc(first) + ", your order has been delivered. We hope you love it!";
                    introText = "Your order #" + orderId + " has been delivered. We hope you love it!";
                    body.append(paragraph(intro));
                    body.append(itemsTable(lines));
                    body.append(paragraph("Got a minute? Leaving a review on the product page helps other students "
                            + "shop with confidence. If something's wrong, use <em>Report a problem</em> in My Orders."));
                    body.append(button("Review my items", "orders.html"));
                    break;
                case CANCELLED:
                    subject = "Your order #" + orderId + " was cancelled";
                    title = "Order cancelled";
                    intro = "Hi " + esc(first) + ", your order has been cancelled as requested. "
                            + "The seller has been told not to send it.";
                    introText = "Your order #" + orderId + " has been cancelled.";
                    body.append(paragraph(intro));
                    body.append(itemsTable(lines));
                    body.append(simpleTotal("Order total", order.getTotalAmount()));
                    body.append(paragraph("Changed your mind? You can order the items again any time from the marketplace."));
                    break;
                default:
                    return;
            }

            String html = layout(title, "Order #" + orderId + " \u00B7 " + dateOf(order), body.toString());
            String text = "Hi " + first + ",\n\n" + introText + "\n\n" + linesText(lines)
                    + (courierText != null && newStatus == Order.OrderStatus.SHIPPED ? "\nCourier: " + courierText + "\n" : "")
                    + "\n- Zentra Mart";
            emailService.sendHtmlLater(buyer.getEmail(), subject, html, text);
        } catch (Exception e) {
            System.out.println("[OrderEmailService] Could not prepare status email for #" + orderId + ": " + e.getMessage());
        }
    }

    // ======================= data helpers =======================

    private static class Line {
        Long sellerId;
        String title;
        String imageUrl;
        int quantity;
        BigDecimal unitPrice;
        BigDecimal lineTotal;
    }

    private List<Line> loadLines(Long orderId) {
        List<Line> lines = new ArrayList<>();
        for (OrderItem oi : orderItemRepository.findByOrderId(orderId)) {
            Line l = new Line();
            l.sellerId = oi.getSellerId();
            l.quantity = oi.getQuantity() == null ? 0 : oi.getQuantity();
            l.unitPrice = oi.getPriceAtPurchase() == null ? BigDecimal.ZERO : oi.getPriceAtPurchase();
            l.lineTotal = l.unitPrice.multiply(BigDecimal.valueOf(l.quantity));
            Product p = productRepository.findById(oi.getProductId()).orElse(null);
            l.title = p == null ? "Product no longer available" : p.getTitle();
            l.imageUrl = p == null ? null : p.getImageUrl();
            lines.add(l);
        }
        return lines;
    }

    // Skips deleted/deactivated accounts - their email address is no longer theirs
    private User activeUser(Long userId) {
        if (userId == null) return null;
        User u = userRepository.findById(userId).orElse(null);
        if (u == null || u.getStatus() == User.Status.DEACTIVATED) return null;
        if (u.getEmail() == null || !u.getEmail().contains("@")) return null;
        return u;
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "there";
        return fullName.trim().split("\\s+")[0];
    }

    private static String dateOf(Order order) {
        LocalDateTime t = order.getCreatedAt() == null ? LocalDateTime.now() : order.getCreatedAt();
        return t.format(DATE);
    }

    private static String money(BigDecimal amount) {
        if (amount == null) amount = BigDecimal.ZERO;
        return "Rs " + new DecimalFormat("#,##0.00").format(amount);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String linesText(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            sb.append("  ").append(l.quantity).append(" x ").append(l.title)
                    .append("  -  ").append(money(l.lineTotal)).append("\n");
        }
        return sb.toString();
    }

    private static String totalsText(Order order, List<Line> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Line l : lines) subtotal = subtotal.add(l.lineTotal);
        StringBuilder sb = new StringBuilder("Subtotal: " + money(subtotal) + "\n");
        if (order.getDiscountAmount() != null && order.getDiscountAmount().signum() > 0) {
            sb.append("Discount (").append(order.getDiscountCode()).append("): -").append(money(order.getDiscountAmount())).append("\n");
        }
        sb.append("Total paid: ").append(money(order.getTotalAmount())).append("\n");
        return sb.toString();
    }

    // ======================= HTML building blocks =======================
    // Emails need old-school tables + inline styles - Gmail ignores <style> tags and modern CSS.

    private String layout(String title, String subtitle, String content) {
        return "<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:#f2f2f0;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f2f2f0;padding:32px 12px;\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;border-radius:14px;overflow:hidden;font-family:Helvetica,Arial,sans-serif;color:#111111;\">"
                + "<tr><td style=\"background:#111111;padding:22px 32px;\">"
                + "<span style=\"font-family:Georgia,'Times New Roman',serif;font-style:italic;font-size:24px;color:#ffffff;\">Zentra Mart</span>"
                + "</td></tr>"
                + "<tr><td style=\"height:4px;background:" + ACCENT + ";font-size:0;line-height:0;\">&nbsp;</td></tr>"
                + "<tr><td style=\"padding:30px 32px 6px;\">"
                + "<h1 style=\"margin:0;font-size:24px;line-height:1.3;color:#111111;\">" + esc(title) + "</h1>"
                + "<p style=\"margin:6px 0 0;font-size:13px;color:#737373;\">" + esc(subtitle) + "</p>"
                + "</td></tr>"
                + "<tr><td style=\"padding:10px 32px 30px;\">" + content + "</td></tr>"
                + "<tr><td style=\"padding:20px 32px;background:#fafafa;border-top:1px solid #eeeeee;font-size:12px;line-height:1.6;color:#8a8a8a;\">"
                + "You're getting this email because you have an account on Zentra Mart, the student marketplace.<br>"
                + "Need help? Reply to this email or use <em>Report a problem</em> in My Orders."
                + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private static String paragraph(String html) {
        return "<p style=\"margin:16px 0;font-size:15px;line-height:1.6;color:#333333;\">" + html + "</p>";
    }

    private static String itemsTable(List<Line> lines) {
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:18px 0 6px;border-top:1px solid #eeeeee;\">");
        for (Line l : lines) {
            String img = l.imageUrl != null && l.imageUrl.startsWith("http")
                    ? "<img src=\"" + esc(l.imageUrl) + "\" width=\"56\" height=\"56\" alt=\"\" style=\"display:block;width:56px;height:56px;object-fit:cover;border-radius:8px;background:#f0f0f0;\">"
                    : "<div style=\"width:56px;height:56px;border-radius:8px;background:#f0f0f0;\"></div>";
            sb.append("<tr>")
                    .append("<td width=\"68\" style=\"padding:12px 0;border-bottom:1px solid #eeeeee;vertical-align:middle;\">").append(img).append("</td>")
                    .append("<td style=\"padding:12px 8px;border-bottom:1px solid #eeeeee;vertical-align:middle;font-size:14px;\">")
                    .append("<div style=\"font-weight:600;color:#111111;\">").append(esc(l.title)).append("</div>")
                    .append("<div style=\"color:#737373;font-size:13px;margin-top:3px;\">Qty ").append(l.quantity)
                    .append(" &times; ").append(money(l.unitPrice)).append("</div></td>")
                    .append("<td align=\"right\" style=\"padding:12px 0;border-bottom:1px solid #eeeeee;vertical-align:middle;font-size:14px;font-weight:600;white-space:nowrap;\">")
                    .append(money(l.lineTotal)).append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    private static String totalsTable(Order order, List<Line> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Line l : lines) subtotal = subtotal.add(l.lineTotal);
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 8px;font-size:14px;\">");
        sb.append(totalRow("Subtotal", money(subtotal), false, null));
        if (order.getDiscountAmount() != null && order.getDiscountAmount().signum() > 0) {
            sb.append(totalRow("Discount (" + esc(order.getDiscountCode()) + ")",
                    "&minus;" + money(order.getDiscountAmount()), false, "#16a34a"));
        }
        sb.append(totalRow("Total paid", money(order.getTotalAmount()), true, null));
        return sb.append("</table>").toString();
    }

    private static String simpleTotal(String label, BigDecimal amount) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 8px;font-size:14px;\">"
                + totalRow(label, money(amount), true, null) + "</table>";
    }

    private static String totalRow(String label, String value, boolean bold, String color) {
        String weight = bold ? "font-weight:700;font-size:16px;padding-top:10px;" : "color:#555555;";
        String c = color == null ? "" : "color:" + color + ";";
        return "<tr><td style=\"padding:4px 0;" + weight + c + "\">" + label + "</td>"
                + "<td align=\"right\" style=\"padding:4px 0;" + weight + c + "white-space:nowrap;\">" + value + "</td></tr>";
    }

    private static String infoBox(String heading, String html) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:16px 0;\">"
                + "<tr><td style=\"background:#f7f7f5;border-radius:10px;padding:14px 16px;font-size:14px;line-height:1.55;color:#333333;\">"
                + "<div style=\"font-size:11px;letter-spacing:0.06em;text-transform:uppercase;color:#8a8a8a;margin-bottom:4px;\">"
                + esc(heading) + "</div>" + html + "</td></tr></table>";
    }

    // Only shown when zentra.frontend-url is set (files opened from your PC have no web address)
    private String button(String label, String page) {
        if (frontendUrl == null || frontendUrl.isBlank()) return "";
        String base = frontendUrl.endsWith("/") ? frontendUrl : frontendUrl + "/";
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:22px 0 4px;\"><tr>"
                + "<td style=\"background:#111111;border-radius:10px;\">"
                + "<a href=\"" + esc(base + page) + "\" style=\"display:inline-block;padding:13px 26px;font-size:14px;font-weight:600;color:#ffffff;text-decoration:none;\">"
                + esc(label) + "</a></td></tr></table>";
    }
}