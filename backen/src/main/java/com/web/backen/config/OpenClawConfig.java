package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "openclaw")
public class OpenClawConfig {

    private String command = "openclaw";
    private String sessionKey = "main";
    private int timeoutSeconds = 30;
    private int maxConcurrentCalls = 2;
    private int historyCacheMillis = 1000;
    private int sessionsCacheMillis = 5000;
    private int modelsCacheMillis = 60000;
    private int commandsCacheMillis = 300000;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
    public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }
    public int getHistoryCacheMillis() { return historyCacheMillis; }
    public void setHistoryCacheMillis(int historyCacheMillis) { this.historyCacheMillis = historyCacheMillis; }
    public int getSessionsCacheMillis() { return sessionsCacheMillis; }
    public void setSessionsCacheMillis(int sessionsCacheMillis) { this.sessionsCacheMillis = sessionsCacheMillis; }
    public int getModelsCacheMillis() { return modelsCacheMillis; }
    public void setModelsCacheMillis(int modelsCacheMillis) { this.modelsCacheMillis = modelsCacheMillis; }
    public int getCommandsCacheMillis() { return commandsCacheMillis; }
    public void setCommandsCacheMillis(int commandsCacheMillis) { this.commandsCacheMillis = commandsCacheMillis; }
}
