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
    }

    private void addColumnIfMissing(String table, String column) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE LOWER(TABLE_NAME)=LOWER(?) AND LOWER(COLUMN_NAME)=LOWER(?)
                """, Integer.class, table, column);
        if (found != null && found > 0) return;
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT");
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
