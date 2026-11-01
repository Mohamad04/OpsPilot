package com.opspilot.backend.web.dto;

import org.springframework.data.domain.Page;
import java.util.List;

public record OrganisationMemberPageResponse(List<OrganisationMemberResponse> content, int page, int size,
        long totalElements, int totalPages, boolean first, boolean last) {
    public OrganisationMemberPageResponse { content = List.copyOf(content); }
    public static OrganisationMemberPageResponse from(Page<OrganisationMemberResponse> page) {
        return new OrganisationMemberPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast());
    }
}
