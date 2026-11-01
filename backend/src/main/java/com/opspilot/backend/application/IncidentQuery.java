package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.InvalidIncidentQueryException;
import com.opspilot.backend.domain.AlertSeverity;
import com.opspilot.backend.domain.IncidentStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.UUID;

public record IncidentQuery(IncidentStatus status, AlertSeverity severity, UUID serviceId, int page, int size, String sort) {
    public IncidentQuery {
        validatePagination(page, size);
        if (!"createdAt,desc".equals(sort) && !"createdAt,asc".equals(sort)) {
            throw new InvalidIncidentQueryException("sort must be createdAt,desc or createdAt,asc");
        }
    }

    public static void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new InvalidIncidentQueryException("Invalid pagination: page must be nonnegative, size 1-100, and offset at most 2147483647");
        }
    }

    public PageRequest pageable() {
        Sort.Direction direction = sort.endsWith("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(page, size, Sort.by(new Sort.Order(direction, "createdAt"), new Sort.Order(direction, "id")));
    }
}
