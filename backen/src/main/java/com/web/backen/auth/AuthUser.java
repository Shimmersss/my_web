package com.web.backen.auth;

public record AuthUser(long id, String username, String role, int credits, boolean enabled) {
    public boolean isRoot() {
        return "ROOT".equalsIgnoreCase(role);
    }

    public boolean isMatchmakingTrial() {
        return "MATCHMAKING_TRIAL".equalsIgnoreCase(role);
    }
}
