package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "auth")
public class AuthConfig {
    private String rootUsername = "root";
    private String rootPassword = "";
    private int sessionDays = 30;
    private boolean cookieSecure = false;

    public String getRootUsername() { return rootUsername; }
    public void setRootUsername(String rootUsername) { this.rootUsername = rootUsername; }
    public String getRootPassword() { return rootPassword; }
    public void setRootPassword(String rootPassword) { this.rootPassword = rootPassword; }
    public int getSessionDays() { return sessionDays; }
    public void setSessionDays(int sessionDays) { this.sessionDays = sessionDays; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
}
