package com.opspilot.backend.domain;

import org.springframework.data.repository.Repository;
import java.util.UUID;

public interface MembershipAuditEventRepository extends Repository<MembershipAuditEvent, UUID> {
    MembershipAuditEvent save(MembershipAuditEvent event);
}
