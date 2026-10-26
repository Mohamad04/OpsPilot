package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.AlertNotFoundException;
import com.opspilot.backend.application.exception.MonitoredServiceNotFoundException;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.web.dto.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AlertQueryService {
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("firstSeenAt"), Sort.Order.desc("id"));
    private final AlertRepository alerts;
    private final MonitoredServiceRepository services;
    private final AuthenticatedUserService authenticatedUsers;
    private final OrganisationMembershipService memberships;

    public AlertQueryService(AlertRepository alerts, MonitoredServiceRepository services,
                              AuthenticatedUserService authenticatedUsers, OrganisationMembershipService memberships) {
        this.alerts = alerts; this.services = services;
        this.authenticatedUsers = authenticatedUsers; this.memberships = memberships;
    }

    public AlertPageResponse list(String subject, UUID organisationId, AlertQuery filter) {
        requireMembership(subject, organisationId);
        if (filter.serviceId() != null) {
            services.findByIdAndOrganisationId(filter.serviceId(), organisationId)
                    .orElseThrow(() -> new MonitoredServiceNotFoundException(filter.serviceId()));
        }
        Specification<Alert> scoped = (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(builder.equal(root.get("organisationId"), organisationId));
            if (filter.status() != null) predicates.add(builder.equal(root.get("status"), filter.status()));
            if (filter.severity() != null) predicates.add(builder.equal(root.get("severity"), filter.severity()));
            if (filter.serviceId() != null) predicates.add(builder.equal(root.get("monitoredServiceId"), filter.serviceId()));
            if (filter.from() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("firstSeenAt"), AlertQuery.databaseBound(filter.from())));
            if (filter.to() != null) predicates.add(builder.lessThan(root.get("firstSeenAt"), AlertQuery.databaseBound(filter.to())));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return AlertPageResponse.from(alerts.findAll(scoped, PageRequest.of(filter.page(), filter.size(), NEWEST_FIRST))
                .map(AlertResponse::from));
    }

    public AlertResponse details(String subject, UUID organisationId, UUID alertId) {
        requireMembership(subject, organisationId);
        return alerts.findByIdAndOrganisationId(alertId, organisationId).map(AlertResponse::from)
                .orElseThrow(AlertNotFoundException::new);
    }

    public AlertSummaryResponse summary(String subject, UUID organisationId) {
        requireMembership(subject, organisationId);
        var bySeverity = new EnumMap<AlertSeverity, Long>(AlertSeverity.class);
        for (AlertSeverity severity : AlertSeverity.values()) bySeverity.put(severity, 0L);
        long active = 0, resolved = 0;
        // At most eight database-aggregated groups; no alert history is materialised.
        for (var group : alerts.countByStatusAndSeverity(organisationId)) {
            if (group.getStatus() == AlertStatus.ACTIVE) {
                active += group.getCount(); bySeverity.put(group.getSeverity(), group.getCount());
            } else if (group.getStatus() == AlertStatus.RESOLVED) resolved += group.getCount();
        }
        return new AlertSummaryResponse(active + resolved, active, resolved, bySeverity);
    }

    private void requireMembership(String subject, UUID organisationId) {
        User user = authenticatedUsers.findByIdentityProviderSubject(subject);
        memberships.requireMembership(organisationId, user.getId());
    }
}
