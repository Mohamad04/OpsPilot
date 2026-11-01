package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.*;
import com.opspilot.backend.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganisationMemberAdministrationServiceTest {
    @Mock OrganisationMembershipRepository memberships;
    @Mock OrganisationRepository organisations;
    @Mock UserRepository users;
    @Mock OrganisationService organisationService;
    @Mock AuthenticatedUserService authenticatedUserService;
    @Mock MembershipAuditEventRepository audit;
    @Mock Clock clock;
    @Mock EntityManager entityManager;
    @InjectMocks OrganisationMembershipService application;
    private Organisation org;
    private User actor, target;
    private OrganisationMembership actorMember, targetMember;
    private final UUID orgId = UUID.randomUUID(), actorId = UUID.randomUUID(), targetId = UUID.randomUUID(), memberId = UUID.randomUUID();

    @BeforeEach void identities() {
        org = Organisation.create("Unit organisation", "unit"); ReflectionTestUtils.setField(org, "id", orgId);
        actor = User.create("trusted-subject", "Actor", "actor@example.test"); ReflectionTestUtils.setField(actor, "id", actorId);
        target = User.create("target-subject", "Target", "target@example.test"); ReflectionTestUtils.setField(target, "id", targetId);
        actorMember = OrganisationMembership.create(org, actor, OrganisationRole.OWNER);
        targetMember = OrganisationMembership.create(org, target, OrganisationRole.ENGINEER); ReflectionTestUtils.setField(targetMember, "id", memberId);
        when(authenticatedUserService.findByIdentityProviderSubject("trusted-subject")).thenReturn(actor);
    }
    private void lockedOwner() {
        when(memberships.existsByOrganisationIdAndUserId(orgId, actorId)).thenReturn(true);
        when(organisations.lockMembershipAdministration(orgId)).thenReturn(Optional.of(org));
        when(memberships.findByOrganisationIdAndUserId(orgId, actorId)).thenReturn(Optional.of(actorMember));
    }
    @Test void nonMemberCannotLockOrEnumerateAnotherTenant() {
        assertThrows(OrganisationMembershipNotFoundException.class,
                () -> application.deleteMembership("trusted-subject", orgId, memberId));
        verifyNoInteractions(organisations, audit); verify(memberships, never()).findByIdAndOrganisationId(any(), any());
    }
    @Test void revokedMembershipIsCheckedAfterParentLockAndBeforeTargetLookup() {
        when(memberships.existsByOrganisationIdAndUserId(orgId, actorId)).thenReturn(true);
        when(organisations.lockMembershipAdministration(orgId)).thenReturn(Optional.of(org));
        assertThrows(OrganisationMembershipNotFoundException.class,
                () -> application.deleteMembership("trusted-subject", orgId, memberId));
        var order = inOrder(memberships, organisations);
        order.verify(memberships).existsByOrganisationIdAndUserId(orgId, actorId);
        order.verify(organisations).lockMembershipAdministration(orgId);
        order.verify(memberships).findByOrganisationIdAndUserId(orgId, actorId);
        verify(memberships, never()).findByIdAndOrganisationId(any(), any()); verifyNoInteractions(audit);
    }
    @Test void unknownAndForeignMembershipsNeverReachUnscopedRoleChanges() {
        lockedOwner();
        assertThrows(OrganisationMembershipNotFoundException.class,
                () -> application.updateMembership("trusted-subject", orgId, memberId, OrganisationRole.VIEWER));
        verify(memberships).findByIdAndOrganisationId(memberId, orgId); verifyNoInteractions(audit);
    }
    @Test void roleChangeAuditsTrustedActorAndOriginalRole() {
        lockedOwner();
        when(memberships.findByIdAndOrganisationId(memberId, orgId)).thenReturn(Optional.of(targetMember));
        when(memberships.findByOrganisationIdAndUserId(orgId, targetId)).thenReturn(Optional.of(targetMember));
        when(memberships.save(targetMember)).thenReturn(targetMember);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-29T10:00:00Z"));
        assertEquals(OrganisationRole.VIEWER, application.updateMembership("trusted-subject", orgId, memberId, OrganisationRole.VIEWER).role());
        var receipt = ArgumentCaptor.forClass(MembershipAuditEvent.class); verify(audit).save(receipt.capture());
        assertEquals(actorId, ReflectionTestUtils.getField(receipt.getValue(), "actorUserId"));
        assertEquals(OrganisationRole.ENGINEER, ReflectionTestUtils.getField(receipt.getValue(), "previousRole"));
        assertEquals(OrganisationRole.VIEWER, ReflectionTestUtils.getField(receipt.getValue(), "newRole"));
    }
    @Test void unchangedRoleDoesNotAppendAnAuditEvent() {
        lockedOwner();
        when(memberships.findByIdAndOrganisationId(memberId, orgId)).thenReturn(Optional.of(targetMember));
        when(memberships.findByOrganisationIdAndUserId(orgId, targetId)).thenReturn(Optional.of(targetMember));
        when(memberships.save(targetMember)).thenReturn(targetMember);
        application.updateMembership("trusted-subject", orgId, memberId, OrganisationRole.ENGINEER);
        verifyNoInteractions(audit, clock);
    }
    @Test void removalFlushesDatabaseUnassignmentBeforeAuditAppend() {
        lockedOwner();
        when(memberships.findByIdAndOrganisationId(memberId, orgId)).thenReturn(Optional.of(targetMember));
        when(memberships.findByOrganisationIdAndUserId(orgId, targetId)).thenReturn(Optional.of(targetMember));
        when(clock.instant()).thenReturn(Instant.now());
        application.deleteMembership("trusted-subject", orgId, memberId);
        var order = inOrder(memberships, entityManager, audit);
        order.verify(memberships).delete(targetMember); order.verify(entityManager).flush();
        order.verify(audit).save(any(MembershipAuditEvent.class));
    }
    @Test void inactiveUsersCannotBeEnrolled() {
        lockedOwner();
        when(organisationService.findById(orgId)).thenReturn(org);
        ReflectionTestUtils.setField(target, "status", UserStatus.INACTIVE);
        when(users.findById(targetId)).thenReturn(Optional.of(target));
        assertThrows(UserNotFoundException.class,
                () -> application.addMembership("trusted-subject", orgId, targetId, OrganisationRole.VIEWER));
        verify(memberships, never()).save(any()); verifyNoInteractions(audit);
    }
}
