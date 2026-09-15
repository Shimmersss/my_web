package com.web.backen.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/** Adds the durable daily tarot snapshot without rewriting historical check-ins. */
@Component
public class DailyCheckinSchemaMigration {
    private final JdbcTemplate jdbc;

    public DailyCheckinSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void addDailyTarotColumns() {
        addColumnIfMissing("deck_version", "VARCHAR(64) NULL");
        addColumnIfMissing("card_id", "VARCHAR(64) NULL");
        addColumnIfMissing("tarot_payload", "TEXT NULL");
    }

    private void addColumnIfMissing(String column, String definition) {
        if (columnExists(column)) return;
        jdbc.execute("ALTER TABLE daily_checkins ADD COLUMN " + column + " " + definition);
    }

    private boolean columnExists(String column) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String schema = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")
                    ? null : connection.getSchema();
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), schema, "%", "%")) {
                while (columns.next()) {
                    if ("daily_checkins".equalsIgnoreCase(columns.getString("TABLE_NAME"))
                            && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
                return false;
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法读取签到表结构", e);
        }
    }
}
