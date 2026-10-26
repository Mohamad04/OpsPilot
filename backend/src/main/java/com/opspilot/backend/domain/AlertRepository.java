package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends ListCrudRepository<Alert, UUID>, JpaSpecificationExecutor<Alert> {
    Optional<Alert> findByIdAndOrganisationId(UUID id, UUID organisationId);

    @Query("""
            select a.status as status, a.severity as severity, count(a) as count
            from Alert a where a.organisationId = :organisationId
            group by a.status, a.severity
            """)
    List<StatusSeverityCount> countByStatusAndSeverity(@Param("organisationId") UUID organisationId);

    interface StatusSeverityCount {
        AlertStatus getStatus();
        AlertSeverity getSeverity();
        long getCount();
    }

    Optional<Alert> findByOrganisationIdAndMonitoredServiceIdAndCategoryAndStatus(
            UUID organisationId, UUID monitoredServiceId, AlertCategory category, AlertStatus status);
    List<Alert> findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(UUID organisationId, UUID monitoredServiceId);
}
