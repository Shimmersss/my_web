package com.web.backen.moonlit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
public class MoonlitSchemaMigration {
    private final JdbcTemplate jdbc;
    public MoonlitSchemaMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @PostConstruct
    void migrate() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS moonlit_collections (user_id BIGINT NOT NULL, card_id VARCHAR(32) NOT NULL, acquisitions INT NOT NULL DEFAULT 0, display_theme INT NOT NULL DEFAULT 0, first_acquired_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (user_id, card_id), FOREIGN KEY (user_id) REFERENCES users(id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS moonlit_draws (id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, card_id VARCHAR(32) NOT NULL, method VARCHAR(16) NOT NULL, result_type VARCHAR(16) NOT NULL, acquisition_count INT NOT NULL, draw_date DATE NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (user_id) REFERENCES users(id))");
        try { jdbc.execute("CREATE INDEX idx_moonlit_draws_user_created ON moonlit_draws(user_id, created_at)"); } catch (Exception ignored) { }
    }
}
