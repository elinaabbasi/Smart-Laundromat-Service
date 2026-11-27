package com.smartlaundromat.business;

import java.util.List;

import com.smartlaundromat.model.Notification;
import com.smartlaundromat.dataaccess.NotificationRepository;

import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public List<Notification> recentForCustomer(String email) {
        return notifications.findRecentForCustomer(email, 20);
    }
}
