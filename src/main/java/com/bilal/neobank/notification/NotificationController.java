package com.bilal.neobank.notification;

import com.bilal.neobank.notification.NotificationService.Inbox;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The signed-in customer's own notices; someone else's id is simply "not found". */
@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/notifications")
    public Inbox inbox(@AuthenticationPrincipal Jwt jwt) {
        return service.inbox(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/api/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!service.markRead(UUID.fromString(jwt.getSubject()), id)) {
            throw new NotificationNotFoundException();
        }
    }

    @PostMapping("/api/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(UUID.fromString(jwt.getSubject()));
    }
}
