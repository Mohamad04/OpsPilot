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
class IncidentCollaborationMigrationTest {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");

    @Test void upgradingV11PreservesAssignedIncidentsAndLegacyEventsAndAuditsLaterMembershipRemoval() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("11").load().migrate();
        UUID org = UUID.randomUUID(), user = UUID.randomUUID(), incident = UUID.randomUUID(), event = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            execute(connection, "insert into organisations(id,name,slug,status,created_at) values (?,'Migration fixture','collaboration-migration','ACTIVE',now())", org);
            execute(connection, "insert into users(id,identity_provider_subject,display_name,email,status) values (?,'migration-subject','Member','migration@example.test','ACTIVE')", user);
            execute(connection, "insert into organisation_memberships(id,organisation_id,user_id,role) values (?,?,?,'ENGINEER')", UUID.randomUUID(), org, user);
            execute(connection, "insert into incidents(id,organisation_id,title,description,severity,assigned_user_id,created_at) values (?,?,'Existing incident','Original description','HIGH',?,now())", incident, org, user);
            execute(connection, "insert into incident_events(id,organisation_id,incident_id,event_type,actor_user_id,occurred_at,details) values (?,?,?,'ASSIGNMENT_CHANGED',?,now(),'Original legacy assignment')", event, org, incident, user);
            var current = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load();
            current.migrate(); current.validate(); assertEquals("13", current.info().current().getVersion().getVersion());
            try (var statement = connection.createStatement(); var row = statement.executeQuery("select assigned_user_id,version from incidents")) {
                assertTrue(row.next()); assertEquals(user, row.getObject(1, UUID.class)); assertEquals(0, row.getLong(2));
            }
            try (var statement = connection.createStatement(); var row = statement.executeQuery("select details,previous_assignee_user_id,new_assignee_user_id,client_request_id from incident_events")) {
                assertTrue(row.next()); assertEquals("Original legacy assignment", row.getString(1));
                assertNull(row.getObject(2)); assertNull(row.getObject(3)); assertNull(row.getObject(4)); assertFalse(row.next());
            }
            execute(connection, "delete from organisation_memberships where organisation_id=? and user_id=?", org, user);
            try (var statement = connection.createStatement(); var row = statement.executeQuery("select assigned_user_id,version from incidents")) {
                assertTrue(row.next()); assertNull(row.getObject(1)); assertEquals(1, row.getLong(2));
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select actor_user_id,previous_assignee_user_id,new_assignee_user_id from incident_events order by occurred_at")) {
                assertTrue(rows.next()); assertEquals(user, rows.getObject(1, UUID.class));
                assertTrue(rows.next()); assertNull(rows.getObject(1)); assertEquals(user, rows.getObject(2, UUID.class)); assertNull(rows.getObject(3)); assertFalse(rows.next());
            }
            assertEquals("55000", assertThrows(SQLException.class, () -> execute(connection, "delete from incident_events where id=?", event)).getSQLState());
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i+1, values[i]);
            statement.executeUpdate();
        }
    }
}
