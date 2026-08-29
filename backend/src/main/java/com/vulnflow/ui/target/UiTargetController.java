package com.vulnflow.ui.target;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/ui/v1/targets")
public class UiTargetController {
    private final UiTargetRepository targets;
    public UiTargetController(UiTargetRepository targets) { this.targets = targets; }
    @GetMapping public List<TargetResponse> list() { return targets.findByEnabledTrueOrderByNameAsc().stream().map(TargetResponse::from).toList(); }
    public record TargetResponse(UUID id, String name, String type, boolean enabled, Instant updatedAt) {
        static TargetResponse from(UiTarget target) { return new TargetResponse(target.getId(), target.getName(), target.getType().name(), target.isEnabled(), target.getUpdatedAt()); }
    }
}
