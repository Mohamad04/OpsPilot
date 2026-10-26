package com.opspilot.backend.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AlertTest {
    static final UUID ORG = UUID.randomUUID(), SERVICE = UUID.randomUUID();
    static final Instant START = Instant.parse("2026-10-26T12:00:00Z");

    static HealthCheckResult result(HealthStatus status, int second) {
        var result = new HealthCheckResult(ORG, SERVICE, START.plusSeconds(second), START.plusSeconds(second),
                status, status == HealthStatus.DOWN ? Integer.valueOf(503) : status == HealthStatus.UP ? Integer.valueOf(200) : null, 25,
                status == HealthStatus.DOWN ? "Unexpected HTTP status: 503." : null);
        ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
        return result;
    }

    @Test void openingAndFurtherFailuresPreserveTriggerEvidenceAndSeverity() {
        var first = result(HealthStatus.DOWN, 1); var trigger = result(HealthStatus.DOWN, 3);
        var alert = Alert.open(trigger, first.getId(), first.getStartedAt(), 3, 3, 2, AlertSeverity.HIGH);
        assertEquals(AlertStatus.ACTIVE, alert.getStatus()); assertEquals(3, alert.getOccurrenceCount());
        assertEquals(first.getStartedAt(), alert.getFirstSeenAt()); assertEquals(trigger.getStartedAt(), alert.getLastSeenAt());
        assertEquals(trigger.getId(), alert.getTriggeringHealthCheckResultId());
        assertEquals(503, alert.getTriggeringHttpStatusCode()); assertEquals(25, alert.getTriggeringResponseTimeMs());
        assertEquals("Unexpected HTTP status: 503.", alert.getTriggeringFailureDetail());
        var next = result(HealthStatus.DOWN, 4); alert.recordFailure(next);
        assertEquals(4, alert.getOccurrenceCount()); assertEquals(next.getId(), alert.getLastFailureHealthCheckResultId());
        assertEquals(trigger.getId(), alert.getTriggeringHealthCheckResultId());
        assertEquals(AlertSeverity.HIGH, alert.getSeverity()); assertNull(alert.getResolvedAt());
    }

    @Test void resolutionIsFinalAndKeepsHistory() {
        var trigger = result(HealthStatus.DOWN, 3);
        var alert = Alert.open(trigger, trigger.getId(), START, 3, 3, 2, AlertSeverity.CRITICAL);
        var recovery = result(HealthStatus.UP, 5); alert.resolve(recovery, 2);
        assertEquals(AlertStatus.RESOLVED, alert.getStatus()); assertEquals(recovery.getStartedAt(), alert.getResolvedAt());
        assertEquals(recovery.getId(), alert.getResolutionHealthCheckResultId()); assertEquals(2, alert.getResolvedAfterRecoveries());
        assertEquals(3, alert.getOccurrenceCount());
        assertThrows(IllegalStateException.class, () -> alert.recordFailure(result(HealthStatus.DOWN, 6)));
        assertThrows(IllegalStateException.class, () -> alert.resolve(result(HealthStatus.UP, 7), 2));
    }

    @ParameterizedTest @EnumSource(value = HealthStatus.class, names = "DOWN", mode = EnumSource.Mode.EXCLUDE)
    void onlyDownEvidenceCanOpenAnAlert(HealthStatus status) {
        var trigger = result(status, 1);
        assertThrows(IllegalArgumentException.class, () -> Alert.open(trigger, trigger.getId(), START, 3, 3, 2, AlertSeverity.HIGH));
    }

    @Test void invalidThresholdsTimestampsEvidenceAndForeignScopeAreRejected() {
        var trigger = result(HealthStatus.DOWN, 3);
        assertThrows(IllegalArgumentException.class, () -> Alert.open(trigger, trigger.getId(), START, 2, 3, 2, AlertSeverity.HIGH));
        assertThrows(IllegalArgumentException.class, () -> Alert.open(trigger, trigger.getId(), START, 3, 0, 2, AlertSeverity.HIGH));
        assertThrows(IllegalArgumentException.class, () -> Alert.open(trigger, trigger.getId(), START.plusSeconds(9), 3, 3, 2, AlertSeverity.HIGH));
        var alert = Alert.open(trigger, trigger.getId(), START, 3, 3, 2, AlertSeverity.HIGH);
        assertThrows(IllegalArgumentException.class, () -> alert.recordFailure(trigger));
        assertThrows(IllegalArgumentException.class, () -> alert.resolve(result(HealthStatus.UNKNOWN, 4), 2));
        var foreign = result(HealthStatus.DOWN, 4); ReflectionTestUtils.setField(foreign, "organisationId", UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> alert.recordFailure(foreign));
        ReflectionTestUtils.setField(trigger, "id", null);
        assertThrows(NullPointerException.class, () -> Alert.open(trigger, UUID.randomUUID(), START, 3, 3, 2, AlertSeverity.HIGH));
    }
}
