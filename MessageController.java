package com.zentramart.backend.catalog.controller;

import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.catalog.model.Message;
import com.zentramart.backend.catalog.repository.MessageRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/messages")
@CrossOrigin(origins = "*")
public class MessageController {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    @Autowired
    public MessageController(MessageRepository messageRepository, UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    public static class SendMessageRequest {
        public Long senderId;
        public Long receiverId;
        public Long productId;
        public String body;
    }

    // POST /api/messages
    @PostMapping
    public ResponseEntity<?> sendMessage(@RequestBody SendMessageRequest req) {
        if (req.body == null || req.body.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Message cannot be empty.");
        }
        Message m = new Message();
        m.setSenderId(req.senderId);
        m.setReceiverId(req.receiverId);
        m.setProductId(req.productId);
        m.setBody(req.body.trim());
        m.setIsRead(false);
        return ResponseEntity.ok(messageRepository.save(m));
    }

    // GET /api/messages/conversation?userA=5&userB=9
    @GetMapping("/conversation")
    public List<Message> getConversation(@RequestParam Long userA, @RequestParam Long userB) {
        return messageRepository.findConversation(userA, userB);
    }

    public static class InboxThread {
        public Long counterpartId;
        public String counterpartName;
        public String lastMessage;
        public LocalDateTime lastMessageAt;
        public int unreadCount;
    }

    // GET /api/messages/inbox/5   (5 = userId) - one row per conversation partner
    @GetMapping("/inbox/{userId}")
    public List<InboxThread> getInbox(@PathVariable Long userId) {
        List<Message> all = messageRepository.findBySenderIdOrReceiverIdOrderByCreatedAtDesc(userId, userId);

        Map<Long, List<Message>> byCounterpart = new LinkedHashMap<>();
        for (Message m : all) {
            Long counterpart = m.getSenderId().equals(userId) ? m.getReceiverId() : m.getSenderId();
            byCounterpart.computeIfAbsent(counterpart, k -> new ArrayList<>()).add(m);
        }

        return byCounterpart.entrySet().stream().map(entry -> {
            InboxThread t = new InboxThread();
            t.counterpartId = entry.getKey();
            userRepository.findById(entry.getKey()).ifPresent(u -> t.counterpartName = u.getFullName());
            Message last = entry.getValue().get(0); // already sorted desc
            t.lastMessage = last.getBody();
            t.lastMessageAt = last.getCreatedAt();
            t.unreadCount = (int) entry.getValue().stream()
                    .filter(m -> m.getReceiverId().equals(userId) && !Boolean.TRUE.equals(m.getIsRead()))
                    .count();
            return t;
        }).collect(Collectors.toList());
    }

    // PUT /api/messages/read?userId=5&counterpartId=9 - marks messages FROM counterpart TO userId as read
    @PutMapping("/read")
    public ResponseEntity<?> markRead(@RequestParam Long userId, @RequestParam Long counterpartId) {
        List<Message> conv = messageRepository.findConversation(userId, counterpartId);
        conv.stream()
                .filter(m -> m.getReceiverId().equals(userId) && !Boolean.TRUE.equals(m.getIsRead()))
                .forEach(m -> { m.setIsRead(true); messageRepository.save(m); });
        return ResponseEntity.ok().build();
    }
}