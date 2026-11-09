package com.edu.submission.system;

import com.edu.submission.checking.application.CheckTaskService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemHealthController {

    private final JdbcTemplate jdbcTemplate;
    private final CheckTaskService checkTaskService;

    public SystemHealthController(JdbcTemplate jdbcTemplate, CheckTaskService checkTaskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.checkTaskService = checkTaskService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "teaching-service");
        body.put("time", OffsetDateTime.now(ZoneOffset.UTC));
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            body.put("database", "UP");
            body.put("checkQueueLength", checkTaskService.queueLength());
            body.put("checkRunning", checkTaskService.runningCount());
        } catch (RuntimeException exception) {
            body.put("status", "DEGRADED");
            body.put("database", "DOWN");
        }
        return body;
    }
}

