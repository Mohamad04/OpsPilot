package com.opspilot.backend.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static com.opspilot.backend.domain.AlertTest.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertEvaluationStateTest {
    private AlertEvaluationState state() { return AlertEvaluationState.create(ORG, SERVICE, "a".repeat(64), START); }

    @Test void downUpAndUnknownMaintainConsecutiveStreaks() {
        var state = state();
        var first = result(HealthStatus.DOWN, 1);
        assertTrue(state.consume(first, true, START));
        state.consume(result(HealthStatus.DOWN, 2), true, START);
        assertEquals(2, state.getConsecutiveFailures()); assertEquals(first.getId(), state.getFirstFailureHealthCheckResultId());
        state.consume(result(HealthStatus.UP, 3), true, START);
        assertEquals(0, state.getConsecutiveFailures()); assertEquals(1, state.getConsecutiveRecoveries());
        assertNull(state.getFirstFailureAt());
        state.consume(result(HealthStatus.DOWN, 4), true, START);
        assertEquals(1, state.getConsecutiveFailures()); assertEquals(0, state.getConsecutiveRecoveries());
        state.consume(result(HealthStatus.UNKNOWN, 5), true, START);
        assertEquals(0, state.getConsecutiveFailures()); assertEquals(0, state.getConsecutiveRecoveries());
    }

    @Test void duplicatesOlderAndEqualStartTimesNeverRegressCursorOrCounters() {
        var state = state(); var newest = result(HealthStatus.DOWN, 5);
        state.consume(newest, true, START.plusSeconds(9));
        assertFalse(state.consume(newest, true, START.plusSeconds(10)));
        assertFalse(state.consume(result(HealthStatus.UP, 4), true, START.plusSeconds(10)));
        assertFalse(state.consume(result(HealthStatus.UP, 5), true, START.plusSeconds(10)));
        assertEquals(newest.getId(), state.getLastProcessedHealthCheckResultId());
        assertEquals(1, state.getConsecutiveFailures()); assertEquals(START.plusSeconds(9), state.getUpdatedAt());
    }

    @Test void resetPreservesCursorAndBlocksInFlightEvidenceUntilAfterBoundary() {
        var state = state(); var prior = result(HealthStatus.DOWN, 1); state.consume(prior, true, START);
        state.resetConfiguration("b".repeat(64), START.plusSeconds(3), START.plusSeconds(3));
        assertEquals(prior.getId(), state.getLastProcessedHealthCheckResultId()); assertEquals(0, state.getConsecutiveFailures());
        assertFalse(state.consume(result(HealthStatus.DOWN, 2), true, START.plusSeconds(4)));
        assertFalse(state.consume(result(HealthStatus.DOWN, 3), true, START.plusSeconds(4)));
        assertTrue(state.consume(result(HealthStatus.DOWN, 4), true, START.plusSeconds(4)));
        assertEquals(1, state.getConsecutiveFailures());
    }

    @Test void disabledEvaluationConsumesCursorWithoutCountingAndCannotCrossScope() {
        var state = state(); state.consume(result(HealthStatus.DOWN, 1), true, START);
        assertFalse(state.consume(result(HealthStatus.DOWN, 2), false, START));
        assertEquals(0, state.getConsecutiveFailures());
        var foreign = result(HealthStatus.DOWN, 3); ReflectionTestUtils.setField(foreign, "monitoredServiceId", java.util.UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> state.consume(foreign, true, START));
    }

    @Test void countersSaturateRatherThanOverflowAndResetCannotMoveBoundaryBackwards() {
        var state = state(); state.consume(result(HealthStatus.DOWN, 1), true, START);
        ReflectionTestUtils.setField(state, "consecutiveFailures", Integer.MAX_VALUE);
        state.consume(result(HealthStatus.DOWN, 2), true, START);
        assertEquals(Integer.MAX_VALUE, state.getConsecutiveFailures());
        state.resetConfiguration("b".repeat(64), START, START);
        assertEquals(START.plusSeconds(2), state.getConfigurationChangedAt());
    }
}
