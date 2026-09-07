package com.web.backen.matchmaking;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/** Keeps already-created local MySQL tables compatible with the account-owned storage schema. */
@Component
public class MatchmakingSchemaMigration {
    private final JdbcTemplate jdbc;
    public MatchmakingSchemaMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void addAccountOwnedPayloadColumns() {
        addColumnIfMissing("matchmaking_profiles", "payload");
        addColumnIfMissing("matchmaking_reports", "report_payload");
        makeLegacyColumnNullable("matchmaking_profiles", "encrypted_payload");
        makeLegacyColumnNullable("matchmaking_reports", "encrypted_report");
        addColumnIfMissing("matchmaking_reports", "total_score", "DOUBLE NULL");
        addColumnIfMissing("matchmaking_reports", "level", "VARCHAR(20) NULL");
        addColumnIfMissing("matchmaking_reports", "city", "VARCHAR(64) NULL");
        addColumnIfMissing("matchmaking_reports", "has_image", "BOOLEAN NULL");
        ensureReportPayloadCapacity();
        ensureTrialCodesTable();
        addColumnIfMissing("matchmaking_trial_codes", "code_plain", "VARCHAR(64) NULL");
    }

    private void ensureTrialCodesTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS matchmaking_trial_codes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    code_hash VARCHAR(64) NOT NULL UNIQUE,
                    code_plain VARCHAR(64) NULL,
                    code_suffix VARCHAR(4) NOT NULL,
                    guest_user_id BIGINT NULL UNIQUE,
                    status VARCHAR(20) NOT NULL DEFAULT 'UNUSED',
                    active_task_id VARCHAR(36) NULL,
                    report_id VARCHAR(36) NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    expires_at TIMESTAMP NOT NULL,
                    redeemed_at TIMESTAMP NULL,
                    completed_at TIMESTAMP NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (guest_user_id) REFERENCES users(id),
                    FOREIGN KEY (created_by) REFERENCES users(id)
                )
                """);
    }

    /** v3.5 reports embed the AI partner illustration as base64; MySQL TEXT (64KB) is not enough. */
    private void ensureReportPayloadCapacity() {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            if (!product.contains("mysql")) return;
            Long size = jdbc.queryForObject("""
                    SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND LOWER(TABLE_NAME)='matchmaking_reports' AND LOWER(COLUMN_NAME)='report_payload'
                    """, Long.class);
            if (size != null && size > 65535) return;
            jdbc.execute("ALTER TABLE matchmaking_reports MODIFY COLUMN report_payload MEDIUMTEXT NOT NULL");
        } catch (Exception e) { throw new IllegalStateException("婚恋报告载荷列升级失败", e); }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        if (columnExists(table, column)) return;
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void addColumnIfMissing(String table, String column) {
        addColumnIfMissing(table, column, "TEXT");
    }

    private void makeLegacyColumnNullable(String table, String column) {
        if (!columnExists(table, column)) return;
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            if (product.contains("mysql")) jdbc.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " TEXT NULL");
            else jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
        } catch (Exception e) { throw new IllegalStateException("婚恋报告旧表迁移失败", e); }
    }

    private boolean columnExists(String table, String column) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String schema = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")
                    ? null : connection.getSchema();
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), schema, "%", "%")) {
                while (columns.next()) {
                    if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                            && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
                return false;
            }
        } catch (Exception e) { throw new IllegalStateException("无法读取当前数据库结构", e); }
    }
}
