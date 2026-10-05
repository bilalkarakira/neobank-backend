package com.bilal.neobank.notification;

/** Unknown id or someone else's notice: indistinguishable on purpose. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("Notification not found");
    }
}
