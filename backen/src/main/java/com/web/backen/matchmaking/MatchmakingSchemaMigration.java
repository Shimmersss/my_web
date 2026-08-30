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
    }

    /** v3.5 reports embed the AI partner illustration as base64; MySQL TEXT (64KB) is not enough. */
    private void ensureReportPayloadCapacity() {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            if (!product.contains("mysql")) return;
            Long size = jdbc.queryForObject("""
                    SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE LOWER(TABLE_NAME)='matchmaking_reports' AND LOWER(COLUMN_NAME)='report_payload'
                    """, Long.class);
            if (size != null && size > 65535) return;
            jdbc.execute("ALTER TABLE matchmaking_reports MODIFY COLUMN report_payload MEDIUMTEXT NOT NULL");
        } catch (Exception e) { throw new IllegalStateException("婚恋报告载荷列升级失败", e); }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE LOWER(TABLE_NAME)=LOWER(?) AND LOWER(COLUMN_NAME)=LOWER(?)
                """, Integer.class, table, column);
        if (found != null && found > 0) return;
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
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE LOWER(TABLE_NAME)=LOWER(?) AND LOWER(COLUMN_NAME)=LOWER(?)
                """, Integer.class, table, column);
        return found != null && found > 0;
    }
}
