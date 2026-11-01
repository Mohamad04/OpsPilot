package com.opspilot.backend.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class IncidentLifecycleMigrationTest {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");

    @Test void upgradingV10PreservesLegacyImmutableHistoryAndDeduplicatesRecoveryTypes() throws Exception {
        var prior = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("10").load();
        prior.migrate();
        UUID org = UUID.randomUUID(), service = UUID.randomUUID(), incident = UUID.randomUUID(), alert = UUID.randomUUID();
        UUID down = UUID.randomUUID(), up = UUID.randomUUID(), event = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            insert(connection, "insert into organisations(id,name,slug,status,created_at) values (?,'Migration fixture','migration-fixture','ACTIVE','2026-10-27T12:00:00Z')", org);
            insert(connection, "insert into monitored_services(id,organisation_id,name,service_type,environment,base_url,health_endpoint,owner) values (?,?,'Fixture','HTTP','TEST','https://fixture.example.test','/health','Test')", service, org);
            for (UUID result : new UUID[]{down, up}) {
                insert(connection, "insert into health_check_results(id,organisation_id,monitored_service_id,started_at,completed_at,status,http_status_code,response_time_ms) values (?,?,?,'2026-10-27T12:00:00Z','2026-10-27T12:00:00Z',?, ?,1)",
                        result, org, service, result.equals(down) ? "DOWN" : "UP", result.equals(down) ? 503 : 200);
            }
            insert(connection, """
                    insert into alerts(id,organisation_id,monitored_service_id,category,severity,status,first_seen_at,last_seen_at,
                      resolved_at,occurrence_count,failure_threshold,recovery_threshold,resolved_after_recoveries,
                      first_failure_health_check_result_id,triggering_health_check_result_id,last_failure_health_check_result_id,
                      resolution_health_check_result_id,triggering_response_time_ms)
                    values (?,?,?,'HTTP_AVAILABILITY','HIGH','RESOLVED','2026-10-27T12:00:00Z','2026-10-27T12:00:00Z',
                      '2026-10-27T12:00:01Z',3,3,2,2,?,?,?,?,1)
                    """, alert, org, service, down, down, down, up);
            insert(connection, "insert into incidents(id,organisation_id,monitored_service_id,title,description,severity,status,created_at) values (?,?,?,'Legacy incident','Preserved description','HIGH','OPEN','2026-10-27T12:00:00Z')", incident, org, service);
            insert(connection, "insert into incident_alerts(id,organisation_id,incident_id,alert_id) values (?,?,?,?)", UUID.randomUUID(), org, incident, alert);
            insert(connection, "insert into incident_events(id,organisation_id,incident_id,event_type,source_alert_id,occurred_at,details) values (?,?,?,'COMMENT_ADDED',?,'2026-10-27T12:00:01Z','Original recovery evidence')", event, org, incident, alert);
            var current = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load();
            current.migrate(); current.validate(); assertEquals("13", current.info().current().getVersion().getVersion());
            try (var statement = connection.prepareStatement("select event_type,details,source_alert_id,previous_status,new_status,occurred_at from incident_events where id=?")) {
                statement.setObject(1, event);
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next()); assertEquals("COMMENT_ADDED", row.getString(1)); assertEquals("Original recovery evidence", row.getString(2));
                    assertEquals(alert, row.getObject(3, UUID.class)); assertNull(row.getString(4)); assertNull(row.getString(5));
                    assertEquals(java.time.Instant.parse("2026-10-27T12:00:01Z"), row.getTimestamp(6).toInstant());
                }
            }
            assertEquals("23505", assertThrows(SQLException.class, () -> insert(connection,
                    "insert into incident_events(id,organisation_id,incident_id,event_type,source_alert_id,occurred_at,details) values (?,?,?,'ALERT_RESOLVED',?,'2026-10-27T12:00:01Z','Duplicate recovery')",
                    UUID.randomUUID(), org, incident, alert)).getSQLState());
            assertEquals("55000", assertThrows(SQLException.class, () -> insert(connection, "delete from incident_events where id=?", event)).getSQLState());
            try (var statement = connection.createStatement(); var row = statement.executeQuery("select version from incidents")) {
                assertTrue(row.next()); assertEquals(0, row.getLong(1));
            }
        }
    }

    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
}
