package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.InvalidAlertQueryException;
import com.opspilot.backend.domain.AlertSeverity;
import com.opspilot.backend.domain.AlertStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record AlertQuery(AlertStatus status, AlertSeverity severity, UUID serviceId,
                         Instant from, Instant to, int page, int size) {
    private static final Instant MIN_DATE = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_DATE = Instant.parse("+10000-01-01T00:00:00Z");

    public AlertQuery {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new InvalidAlertQueryException("Invalid pagination: page must be nonnegative, size must be 1-100, and offset must not exceed 2147483647");
        }
        validateDate(from); validateDate(to);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidAlertQueryException("from must be earlier than to");
        }
    }

    private static void validateDate(Instant value) {
        if (value != null && (value.isBefore(MIN_DATE) || !value.isBefore(MAX_DATE))) {
            throw new InvalidAlertQueryException("Dates must use years 0001-9999");
        }
    }

    // Persisted timestamps have microsecond precision. Ceil both bounds to preserve [from, to).
    static Instant databaseBound(Instant value) {
        if (value == null) return null;
        Instant truncated = value.truncatedTo(ChronoUnit.MICROS);
        return value.equals(truncated) ? value : truncated.plus(1, ChronoUnit.MICROS);
    }
}
