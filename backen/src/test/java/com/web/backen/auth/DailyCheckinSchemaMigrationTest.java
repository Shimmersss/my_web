package com.web.backen.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Connection;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyCheckinSchemaMigrationTest {
    @Test
    void incrementallyAddsDailyTarotColumnsAndIsIdempotent() throws Exception {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE daily_checkins (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, checkin_date DATE NOT NULL)");
        jdbc.update("INSERT INTO daily_checkins(user_id, checkin_date) VALUES (1, CURRENT_DATE)");
        DailyCheckinSchemaMigration migration = new DailyCheckinSchemaMigration(jdbc);

        migration.addDailyTarotColumns();
        migration.addDailyTarotColumns();

        Set<String> columns = new HashSet<>();
        try (Connection connection = database.getConnection();
             var rows = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), "%", "%")) {
            while (rows.next()) if ("daily_checkins".equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                columns.add(rows.getString("COLUMN_NAME").toLowerCase());
            }
        }
        assertTrue(columns.containsAll(Set.of("deck_version", "card_id", "tarot_payload")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM daily_checkins", Integer.class));
        database.shutdown();
    }
}
