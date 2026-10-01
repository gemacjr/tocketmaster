package com.tocketmaster.event.api;

import com.tocketmaster.event.dto.AdminEventRequest;
import com.tocketmaster.event.svc.AdminEventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/events")
public class AdminEventController {

    private final AdminEventService adminEventService;

    public AdminEventController(AdminEventService adminEventService) {
        this.adminEventService = adminEventService;
    }

    public record CreateBody(
            @NotNull UUID performerId,
            @NotNull UUID venueId,
            @NotBlank String name,
            String description,
            @NotBlank String category,
            @NotNull OffsetDateTime startsAt,
            boolean queueEnabled) {
        AdminEventRequest toRequest() {
            return new AdminEventRequest(performerId, venueId, name, description,
                    category, startsAt, queueEnabled);
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateBody body) {
        UUID id = adminEventService.createEvent(body.toRequest());
        return Map.of("eventId", id.toString());
    }

    @PutMapping("/{eventId}")
    public void update(@PathVariable UUID eventId, @Valid @RequestBody CreateBody body) {
        adminEventService.updateEvent(eventId, body.toRequest());
    }
}
