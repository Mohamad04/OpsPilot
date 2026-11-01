package com.opspilot.backend.web.dto;

import org.springframework.data.domain.Page;
import java.util.List;

public record IncidentEventPageResponse(List<IncidentEventResponse> content, int page, int size, long totalElements,
                                        int totalPages, boolean first, boolean last) {
    public IncidentEventPageResponse { content = List.copyOf(content); }
    public static IncidentEventPageResponse from(Page<IncidentEventResponse> page) {
        return new IncidentEventPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isFirst(), page.isLast());
    }
}
