package com.raidzon.notification.controller;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.notification.dto.Notification;
import com.raidzon.notification.repository.NotificationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** The signed-in account's in-app notifications. Only the owner can read or clear them. */
@RestController @Profile("postgres") @RequestMapping("/api/v1/account/notifications")
public class NotificationController {
    private final NotificationRepository notifications;
    public NotificationController(NotificationRepository notifications) { this.notifications = notifications; }

    @GetMapping public Notification.Inbox inbox(@RequestAttribute("identity") AuthIdentity actor) {
        return notifications.inbox(actor.accountId());
    }
    @PostMapping("/read") @Transactional
    public Notification.Inbox read(@RequestBody Notification.ReadInput input, @RequestAttribute("identity") AuthIdentity actor) {
        if (input.all()) notifications.markAllRead(actor.accountId());
        else {
            List<java.util.UUID> ids = input.ids() == null ? List.of() : input.ids();
            if (ids.size() > 100) throw new IllegalArgumentException("Mark up to 100 notifications at a time.");
            notifications.markRead(actor.accountId(), ids);
        }
        return notifications.inbox(actor.accountId());
    }
}
