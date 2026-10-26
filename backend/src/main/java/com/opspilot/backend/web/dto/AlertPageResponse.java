package com.opspilot.backend.web.dto;

import org.springframework.data.domain.Page;
import java.util.List;

public record AlertPageResponse(List<AlertResponse> content, int page, int size, long totalElements,
                                int totalPages, boolean first, boolean last) {
    public AlertPageResponse { content = List.copyOf(content); }

    public static AlertPageResponse from(Page<AlertResponse> results) {
        return new AlertPageResponse(results.getContent(), results.getNumber(), results.getSize(),
                results.getTotalElements(), results.getTotalPages(), results.isFirst(), results.isLast());
    }
}
