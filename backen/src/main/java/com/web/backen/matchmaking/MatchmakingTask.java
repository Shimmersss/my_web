package com.web.backen.matchmaking;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Async report generation task; persisted as JSON under the task dir so a restart can refund interrupted work. */
final class MatchmakingTask {
    final String id;
    final long userId;
    long transactionId;
    final int credits;
    final boolean includePartnerImage;
    final Map<String, Object> request;
    final String createdAt = Instant.now().toString();
    volatile String status = "queued"; // queued | running | done | error
    volatile String stage = "queued";  // queued | scoring | writing | illustrating | saving | done | error
    volatile String reportId = "";
    volatile String error = "";
    volatile String updatedAt = createdAt;

    MatchmakingTask(String id, long userId, long transactionId, int credits, boolean includePartnerImage,
                    Map<String, Object> request) {
        this.id = id; this.userId = userId; this.transactionId = transactionId; this.credits = credits;
        this.includePartnerImage = includePartnerImage; this.request = request;
    }

    Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id); view.put("status", status); view.put("stage", stage);
        view.put("reportId", reportId); view.put("error", error); view.put("createdAt", createdAt);
        return view;
    }

    Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", id); snapshot.put("userId", userId); snapshot.put("transactionId", transactionId);
        snapshot.put("credits", credits); snapshot.put("includePartnerImage", includePartnerImage);
        snapshot.put("request", request); snapshot.put("status", status); snapshot.put("stage", stage);
        snapshot.put("reportId", reportId); snapshot.put("error", error);
        snapshot.put("createdAt", createdAt); snapshot.put("updatedAt", updatedAt);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    static MatchmakingTask fromSnapshot(Map<String, Object> snapshot) {
        MatchmakingTask task = new MatchmakingTask(
                String.valueOf(snapshot.get("id")),
                snapshot.get("userId") instanceof Number n ? n.longValue() : 0L,
                snapshot.get("transactionId") instanceof Number n ? n.longValue() : 0L,
                snapshot.get("credits") instanceof Number n ? n.intValue() : 0,
                Boolean.TRUE.equals(snapshot.get("includePartnerImage")),
                snapshot.get("request") instanceof Map ? (Map<String, Object>) snapshot.get("request") : Map.of());
        task.status = String.valueOf(snapshot.getOrDefault("status", "error"));
        task.stage = String.valueOf(snapshot.getOrDefault("stage", "error"));
        task.reportId = String.valueOf(snapshot.getOrDefault("reportId", ""));
        task.error = String.valueOf(snapshot.getOrDefault("error", ""));
        Object updatedAt = snapshot.get("updatedAt");
        if (updatedAt != null) task.updatedAt = String.valueOf(updatedAt);
        return task;
    }
}
