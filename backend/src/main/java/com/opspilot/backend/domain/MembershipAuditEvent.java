package com.opspilot.backend.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable receipts for authenticated membership administration; not a user directory. */
@Entity
@Immutable
@Table(name = "membership_audit_events")
public class MembershipAuditEvent {
    public enum Action { ADDED, ROLE_CHANGED, REMOVED }

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, updatable = false) private UUID organisationId;
    @Column(nullable = false, updatable = false) private UUID membershipId;
    @Column(nullable = false, updatable = false) private UUID targetUserId;
    @Column(nullable = false, updatable = false) private UUID actorUserId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 16) private Action action;
    @Enumerated(EnumType.STRING) @Column(updatable = false, length = 20) private OrganisationRole previousRole;
    @Enumerated(EnumType.STRING) @Column(updatable = false, length = 20) private OrganisationRole newRole;
    @Column(nullable = false, updatable = false) private Instant occurredAt;

    protected MembershipAuditEvent() { }

    public static MembershipAuditEvent record(UUID organisationId, UUID membershipId, UUID targetUserId,
            UUID actorUserId, Action action, OrganisationRole previousRole, OrganisationRole newRole, Instant at) {
        var event = new MembershipAuditEvent();
        event.organisationId = Objects.requireNonNull(organisationId);
        event.membershipId = Objects.requireNonNull(membershipId);
        event.targetUserId = Objects.requireNonNull(targetUserId);
        event.actorUserId = Objects.requireNonNull(actorUserId);
        event.action = Objects.requireNonNull(action);
        event.occurredAt = Objects.requireNonNull(at);
        boolean valid = switch (action) {
            case ADDED -> previousRole == null && newRole != null;
            case REMOVED -> previousRole != null && newRole == null;
            case ROLE_CHANGED -> previousRole != null && newRole != null && previousRole != newRole;
        };
        if (!valid) throw new IllegalArgumentException("Invalid membership audit transition");
        event.previousRole = previousRole;
        event.newRole = newRole;
        return event;
    }
}
