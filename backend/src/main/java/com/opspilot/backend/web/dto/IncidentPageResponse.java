package com.opspilot.backend.web.dto;

import org.springframework.data.domain.Page;
import java.util.List;

public record IncidentPageResponse(List<IncidentResponse> content, int page, int size, long totalElements,
                                   int totalPages, boolean first, boolean last) {
    public IncidentPageResponse { content = List.copyOf(content); }
    public static IncidentPageResponse from(Page<IncidentResponse> page) {
        return new IncidentPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isFirst(), page.isLast());
    }
}
