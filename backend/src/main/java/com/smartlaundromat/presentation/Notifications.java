package com.smartlaundromat.presentation;

import java.security.Principal;
import java.util.List;

import com.smartlaundromat.business.NotificationService;
import com.smartlaundromat.model.Notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class Notifications {
    private final NotificationService notifications;

    public Notifications(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<Notification> getNotifications(Principal customer) {
        return notifications.recentForCustomer(customer.getName());
    }
}
