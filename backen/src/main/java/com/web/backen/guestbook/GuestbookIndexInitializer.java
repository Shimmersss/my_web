package com.web.backen.guestbook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds optional guestbook indexes without putting vendor-specific DDL in schema.sql. */
@Component
public class GuestbookIndexInitializer {
    private static final Logger log = LoggerFactory.getLogger(GuestbookIndexInitializer.class);

    private final DataSource dataSource;

    public GuestbookIndexInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        try (Connection connection = dataSource.getConnection()) {
            ensureIndex(connection, "guestbook_entries", "idx_guestbook_entries_parent_created",
                    "CREATE INDEX idx_guestbook_entries_parent_created ON guestbook_entries(parent_id, created_at, id)");
            ensureIndex(connection, "guestbook_entries", "idx_guestbook_entries_author_created",
                    "CREATE INDEX idx_guestbook_entries_author_created ON guestbook_entries(author_id, created_at, id)");
            ensureIndex(connection, "guestbook_likes", "idx_guestbook_likes_user_created",
                    "CREATE INDEX idx_guestbook_likes_user_created ON guestbook_likes(user_id, created_at, id)");
            ensureIndex(connection, "guestbook_notifications", "idx_guestbook_notifications_recipient_read_created",
                    "CREATE INDEX idx_guestbook_notifications_recipient_read_created ON guestbook_notifications(recipient_id, read_at, created_at, id)");
        } catch (SQLException exception) {
            log.warn("留言板索引初始化失败，将继续使用现有表结构", exception);
        }
    }

    private void ensureIndex(Connection connection, String table, String index, String ddl) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                String existing = indexes.getString("INDEX_NAME");
                if (index.equalsIgnoreCase(existing)) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            log.info("已创建留言板索引: {}", index);
        }
    }
}
