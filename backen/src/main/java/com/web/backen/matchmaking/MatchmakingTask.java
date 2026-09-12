package com.web.backen.matchmaking;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** SQL-backed task snapshot; request data is discarded after completion or compensation. */
final class MatchmakingTask {
    final String id;
    final long userId;
    long transactionId;
    final int credits;
    final boolean trial;
    final boolean includePartnerImage;
    final String reportVersion;
    final String tarotDrawId;
    Map<String, Object> request;
    volatile boolean compensationPending;
    String createdAt = Instant.now().toString();
    volatile String status = "queued"; // queued | running | done | error
    volatile String stage = "queued";  // queued | scoring | writing | illustrating | saving | done | error
    volatile String reportId = "";
    volatile String error = "";
    volatile String updatedAt = createdAt;

    MatchmakingTask(String id, long userId, long transactionId, int credits, boolean trial, boolean includePartnerImage,
                    Map<String, Object> request) {
        this(id, userId, transactionId, credits, trial, includePartnerImage, "market-positioning-v3", "", request);
    }

    MatchmakingTask(String id, long userId, long transactionId, int credits, boolean trial, boolean includePartnerImage,
                    String reportVersion, String tarotDrawId, Map<String, Object> request) {
        this.id = id; this.userId = userId; this.transactionId = transactionId; this.credits = credits;
        this.trial = trial; this.includePartnerImage = includePartnerImage; this.reportVersion = reportVersion;
        this.tarotDrawId = tarotDrawId == null ? "" : tarotDrawId; this.request = request;
    }

    Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("compensationPending", compensationPending);
        view.put("id", id); view.put("status", status); view.put("stage", stage);
        view.put("reportVersion", reportVersion);
        view.put("reportId", reportId); view.put("error", error); view.put("createdAt", createdAt);
        return view;
    }

    Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("compensationPending", compensationPending);
        snapshot.put("id", id); snapshot.put("userId", userId); snapshot.put("transactionId", transactionId);
        snapshot.put("credits", credits); snapshot.put("trial", trial); snapshot.put("includePartnerImage", includePartnerImage);
        snapshot.put("reportVersion", reportVersion); snapshot.put("tarotDrawId", tarotDrawId);
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
                Boolean.TRUE.equals(snapshot.get("trial")),
                Boolean.TRUE.equals(snapshot.get("includePartnerImage")),
                String.valueOf(snapshot.getOrDefault("reportVersion", "market-positioning-v3")),
                String.valueOf(snapshot.getOrDefault("tarotDrawId", "")),
                snapshot.get("request") instanceof Map ? (Map<String, Object>) snapshot.get("request") : Map.of());
        task.compensationPending = Boolean.TRUE.equals(snapshot.get("compensationPending"));
        task.createdAt = String.valueOf(snapshot.getOrDefault("createdAt", task.createdAt));
        task.status = String.valueOf(snapshot.getOrDefault("status", "error"));
        task.stage = String.valueOf(snapshot.getOrDefault("stage", "error"));
        task.reportId = String.valueOf(snapshot.getOrDefault("reportId", ""));
        task.error = String.valueOf(snapshot.getOrDefault("error", ""));
        Object updatedAt = snapshot.get("updatedAt");
        if (updatedAt != null) task.updatedAt = String.valueOf(updatedAt);
        return task;
    }
}
