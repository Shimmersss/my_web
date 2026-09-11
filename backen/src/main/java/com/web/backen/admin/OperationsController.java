package com.web.backen.admin;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.runtime.TaskCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/operations")
public class OperationsController {
    private final AuthService auth;
    private final TaskCoordinator tasks;
    public OperationsController(AuthService auth, TaskCoordinator tasks) { this.auth = auth; this.tasks = tasks; }
    public record AdmissionRequest(String action) {}
    @GetMapping public Map<String, Object> status(HttpServletRequest request) {
        auth.requireRoot(request);
        return Map.of("code", 200, "data", tasks.snapshot());
    }
    @PutMapping("/admission") public Map<String, Object> admission(HttpServletRequest request,
            @RequestBody AdmissionRequest body) throws IOException {
        auth.requireCsrf(request); auth.requireRoot(request);
        if ("pause".equals(body.action())) tasks.pause();
        else if ("resume".equals(body.action())) tasks.resume();
        else throw new AuthException(400, "action须为pause或resume");
        return Map.of("code", 200, "data", tasks.snapshot());
    }
}
