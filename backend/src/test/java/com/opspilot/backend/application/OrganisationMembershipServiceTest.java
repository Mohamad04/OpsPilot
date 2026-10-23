package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.InsufficientOrganisationPermissionException;
import com.opspilot.backend.application.exception.OrganisationMembershipAlreadyExistsException;
import com.opspilot.backend.application.exception.OrganisationMembershipNotFoundException;
import com.opspilot.backend.application.exception.UserNotFoundException;
import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrganisationMembershipServiceTest {

    @Mock
    private OrganisationMembershipRepository organisationMembershipRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganisationService organisationService;

    @InjectMocks
    private OrganisationMembershipService organisationMembershipService;

    @Test
    void findMembership_returnsMembershipWhenExists() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User user = User.create(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );

        OrganisationMembership organisationMembership =
                OrganisationMembership.create(
                        organisation,
                        user,
                        OrganisationRole.ADMIN
                );

        UUID organisationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                userId
                        )
        ).thenReturn(
                Optional.of(organisationMembership)
        );

        OrganisationMembership result =
                organisationMembershipService.requireMembership(
                        organisationId,
                        userId
                );

        assertSame(organisationMembership, result);

        verify(organisationMembershipRepository)
                .findByOrganisationIdAndUserId(
                        organisationId,
                        userId
                );
    }

    @Test
    void requireMembership_throwsExceptionWhenMembershipDoesNotExist() {
        UUID organisationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                userId
                        )
        ).thenReturn(Optional.empty());

        assertThrows(
                OrganisationMembershipNotFoundException.class,
                () -> organisationMembershipService.requireMembership(
                        organisationId,
                        userId
                )
        );

        verify(organisationMembershipRepository)
                .findByOrganisationIdAndUserId(
                        organisationId,
                        userId
                );
    }

    @Test
    void getOrganisationMembers_returnsRepositoryResult() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User user1 = User.create(
                "identity-1",
                "Mohamad",
                "mohamad@example.com"
        );

        User user2 = User.create(
                "identity-2",
                "Moh",
                "email@email.com"
        );

        OrganisationMembership membership1 =
                OrganisationMembership.create(
                        organisation,
                        user1,
                        OrganisationRole.ADMIN
                );

        OrganisationMembership membership2 =
                OrganisationMembership.create(
                        organisation,
                        user2,
                        OrganisationRole.ENGINEER
                );

        UUID organisationId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findAllByOrganisationId(organisationId)
        ).thenReturn(
                List.of(
                        membership1,
                        membership2
                )
        );

        List<OrganisationMembership> result =
                organisationMembershipService
                        .getOrganisationMembers(organisationId);

        assertEquals(2, result.size());
        assertSame(membership1, result.get(0));
        assertSame(membership2, result.get(1));

        verify(organisationMembershipRepository)
                .findAllByOrganisationId(organisationId);
    }

    @Test
    void getUserMemberships_returnsRepositoryResult() {
        User user = User.create(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );

        Organisation organisation1 = Organisation.create(
                "acme",
                "acme"
        );

        Organisation organisation2 = Organisation.create(
                "beta",
                "beta"
        );

        OrganisationMembership membership1 =
                OrganisationMembership.create(
                        organisation1,
                        user,
                        OrganisationRole.ADMIN
                );

        OrganisationMembership membership2 =
                OrganisationMembership.create(
                        organisation2,
                        user,
                        OrganisationRole.ENGINEER
                );

        UUID userId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findAllByUserId(userId)
        ).thenReturn(
                List.of(
                        membership1,
                        membership2
                )
        );

        List<OrganisationMembership> result =
                organisationMembershipService
                        .getUserMemberships(userId);

        assertEquals(2, result.size());
        assertSame(membership1, result.get(0));
        assertSame(membership2, result.get(1));

        verify(organisationMembershipRepository)
                .findAllByUserId(userId);
    }

    @Test
    void ownerCanPromoteEngineerToAdmin() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User ownerUser = User.create(
                "owner-identity",
                "Owner",
                "owner@example.com"
        );

        User engineerUser = User.create(
                "engineer-identity",
                "Engineer",
                "engineer@example.com"
        );

        OrganisationMembership ownerMembership =
                OrganisationMembership.create(
                        organisation,
                        ownerUser,
                        OrganisationRole.OWNER
                );

        OrganisationMembership engineerMembership =
                OrganisationMembership.create(
                        organisation,
                        engineerUser,
                        OrganisationRole.ENGINEER
                );

        UUID organisationId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID engineerUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                ownerUserId
                        )
        ).thenReturn(Optional.of(ownerMembership));

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                engineerUserId
                        )
        ).thenReturn(Optional.of(engineerMembership));

        when(
                organisationMembershipRepository
                        .save(engineerMembership)
        ).thenReturn(engineerMembership);

        OrganisationMembership result =
                organisationMembershipService.changeMemberRole(
                        organisationId,
                        ownerUserId,
                        engineerUserId,
                        OrganisationRole.ADMIN
                );

        assertSame(engineerMembership, result);
        assertEquals(
                OrganisationRole.ADMIN,
                result.getRole()
        );

        verify(organisationMembershipRepository)
                .save(engineerMembership);
    }

    @Test
    void AdminCannotPromoteEngineerToAdmin() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );
        User adminUser = User.create(
                "identity1",
                "Admin",
                "admin@example.com"
        );
        User engineerUser = User.create(
                "identity2",
                "Engineer",
                "engineer@example.com"
        );

        OrganisationMembership adminMembership =
                OrganisationMembership.create(
                        organisation,
                        adminUser,
                        OrganisationRole.ADMIN
                );

        OrganisationMembership engineerMembership =
                OrganisationMembership.create(
                        organisation,
                        engineerUser,
                        OrganisationRole.ENGINEER
                );
        UUID organisationId = UUID.randomUUID();
        UUID adminUserId = UUID.randomUUID();
        UUID engineerUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                adminUserId
                        )).thenReturn(Optional.of(adminMembership));
        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                engineerUserId)).thenReturn(Optional.of(engineerMembership));


        assertThrows(InsufficientOrganisationPermissionException.class, () -> organisationMembershipService.changeMemberRole(
                organisationId, adminUserId, engineerUserId, OrganisationRole.ADMIN)
        );
        assertEquals(OrganisationRole.ENGINEER, engineerMembership.getRole());


        verify(
                organisationMembershipRepository,
                never()
        ).save(any(OrganisationMembership.class));
    }

    @Test
    void engineerCannotChangeRoles(){
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );
        User viewerUser = User.create(
                "identity1",
                "Viewer",
                "viewer@example.com"
        );
        User engineerUser = User.create(
                "identity2",
                "Engineer",
                "engineer@example.com"
        );

        OrganisationMembership engineerMembership =
                OrganisationMembership.create(
                        organisation,
                        engineerUser,
                        OrganisationRole.ENGINEER
                );

        OrganisationMembership viewerMembership =
                OrganisationMembership.create(
                        organisation,
                        viewerUser,
                        OrganisationRole.VIEWER
                );

        UUID organisationId = UUID.randomUUID();
        UUID engineerUserId = UUID.randomUUID();
        UUID viewerUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                engineerUserId
                        )
        ).thenReturn(Optional.of(engineerMembership));
        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                viewerUserId
                        )
        ).thenReturn(Optional.of(viewerMembership));

        assertThrows(InsufficientOrganisationPermissionException.class, () -> organisationMembershipService.changeMemberRole(
                organisationId, engineerUserId, viewerUserId, OrganisationRole.ENGINEER
        ));
        verify(
                organisationMembershipRepository,
                never()
        ).save(any(OrganisationMembership.class));
    }

    @Test
    void actorMembershipNotFound_throwsException(){
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User engineerUser = User.create(
                "id1",
                "Engineer",
                "engineer@example.com"
        );

        UUID organisationId = UUID.randomUUID();
        UUID engineerUserId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                actorUserId
                        )
        ).thenReturn(Optional.empty());

        assertThrows(OrganisationMembershipNotFoundException.class,() -> organisationMembershipService.changeMemberRole(
                organisationId,
                actorUserId,
                engineerUserId,
                OrganisationRole.ENGINEER
        ));

        verify(
                organisationMembershipRepository,
                never()
        ).findByOrganisationIdAndUserId(
                organisationId,
                engineerUserId
        );
        verify(
                organisationMembershipRepository,
                never()
        ).save(any(OrganisationMembership.class));
    }

    @Test
    void targetMembershipNotFound_throwsException(){

        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User engineerUser = User.create(
                "id1",
                "Engineer",
                "engineer@example.com"
        );

        UUID organisationId = UUID.randomUUID();
        UUID engineerUserId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();


        OrganisationMembership engineerMembership = OrganisationMembership.create(
                organisation,
                engineerUser,
                OrganisationRole.ENGINEER
        );

        when(
                organisationMembershipRepository
                .findByOrganisationIdAndUserId(
                        organisationId,
                        engineerUserId
                )
        ).thenReturn(Optional.of(engineerMembership));

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                targetUserId
                        )
        ).thenReturn(Optional.empty());

        assertThrows(OrganisationMembershipNotFoundException.class,() -> organisationMembershipService.changeMemberRole(
                organisationId,
                engineerUserId,
                targetUserId,
                OrganisationRole.ENGINEER
        ));

        verify(organisationMembershipRepository)
                .findByOrganisationIdAndUserId(
                        organisationId,
                        targetUserId
                );

        verify(
                organisationMembershipRepository,
                never()
        ).save(any(OrganisationMembership.class));
    }

    @Test
    void duplicateMembership_throwsException(){
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );
        User ownerUser = User.create(
                "id",
                "Owner",
                "owner@example.com"
        );

        User targetUser = User.create(
                "ide",
                "Engineer",
                "engineer@example.com"
        );
        UUID organisationId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        OrganisationMembership ownerMembership =
                OrganisationMembership.create(
                        organisation,
                        ownerUser,
                        OrganisationRole.OWNER
                );

        OrganisationMembership engineerMembership = OrganisationMembership.create(
                organisation,
                targetUser,
                OrganisationRole.ENGINEER
        );
        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                ownerUserId
                        )
        ).thenReturn(Optional.of(ownerMembership));

        when(
                organisationService.findById(organisationId)
        ).thenReturn(organisation);

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                targetUserId
                        )
        ).thenReturn(Optional.of(engineerMembership));

        when(
                userRepository.findById(targetUserId)
        ).thenReturn(Optional.of(targetUser));

        assertThrows(OrganisationMembershipAlreadyExistsException.class, () -> organisationMembershipService.addMember(
                organisationId, ownerUserId, targetUserId, OrganisationRole.ENGINEER
        ));

        verify(
                organisationMembershipRepository,
                never()
        ).save(any(OrganisationMembership.class));
    }

    @Test
    void newUserNotFound_throwsException(){
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User adminUser = User.create(
                "id",
                "Admin",
                "admin@example.com"
        );

        UUID organisationId = UUID.randomUUID();
        UUID adminUserId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        OrganisationMembership adminMembership = OrganisationMembership.create(
                organisation,
                adminUser,
                OrganisationRole.ADMIN
        );

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                adminUserId)).thenReturn(Optional.of(adminMembership));


        when(
                organisationService.findById(organisationId)
        ).thenReturn(organisation);

        when(
                userRepository.findById(targetUserId)
        ).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,() -> organisationMembershipService.addMember(
                organisationId, adminUserId, targetUserId, OrganisationRole.ENGINEER
        ));

        verify(
                organisationMembershipRepository,
                never()
        ).findByOrganisationIdAndUserId(
                organisationId,
                targetUserId
        );

        verify(
                organisationMembershipRepository,
                never()
        ).save(any(OrganisationMembership.class));
    }

    @Test
    void ownerCanRemoveEngineer() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User ownerUser = User.create(
                "owner-id",
                "Owner",
                "owner@example.com"
        );

        User engineerUser = User.create(
                "engineer-id",
                "Engineer",
                "engineer@example.com"
        );

        OrganisationMembership ownerMembership =
                OrganisationMembership.create(
                        organisation,
                        ownerUser,
                        OrganisationRole.OWNER
                );

        OrganisationMembership engineerMembership =
                OrganisationMembership.create(
                        organisation,
                        engineerUser,
                        OrganisationRole.ENGINEER
                );

        UUID organisationId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID engineerUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                ownerUserId
                        )
        ).thenReturn(Optional.of(ownerMembership));

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                engineerUserId
                        )
        ).thenReturn(Optional.of(engineerMembership));

        organisationMembershipService.removeMember(
                organisationId,
                ownerUserId,
                engineerUserId
        );

        verify(organisationMembershipRepository)
                .delete(engineerMembership);
    }

    @Test
    void adminCanRemoveViewer() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User adminUser = User.create(
                "admin-id",
                "Admin",
                "admin@example.com"
        );

        User viewerUser = User.create(
                "viewer-id",
                "Viewer",
                "viewer@example.com"
        );

        OrganisationMembership adminMembership =
                OrganisationMembership.create(
                        organisation,
                        adminUser,
                        OrganisationRole.ADMIN
                );

        OrganisationMembership viewerMembership =
                OrganisationMembership.create(
                        organisation,
                        viewerUser,
                        OrganisationRole.VIEWER
                );

        UUID organisationId = UUID.randomUUID();
        UUID adminUserId = UUID.randomUUID();
        UUID viewerUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                adminUserId
                        )
        ).thenReturn(Optional.of(adminMembership));

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                viewerUserId
                        )
        ).thenReturn(Optional.of(viewerMembership));

        organisationMembershipService.removeMember(
                organisationId,
                adminUserId,
                viewerUserId
        );

        verify(organisationMembershipRepository)
                .delete(viewerMembership);
    }

    @Test
    void adminCannotRemoveAdmin() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User actorUser = User.create(
                "admin-1",
                "Admin One",
                "admin1@example.com"
        );

        User targetUser = User.create(
                "admin-2",
                "Admin Two",
                "admin2@example.com"
        );

        OrganisationMembership actorMembership =
                OrganisationMembership.create(
                        organisation,
                        actorUser,
                        OrganisationRole.ADMIN
                );

        OrganisationMembership targetMembership =
                OrganisationMembership.create(
                        organisation,
                        targetUser,
                        OrganisationRole.ADMIN
                );

        UUID organisationId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                actorUserId
                        )
        ).thenReturn(Optional.of(actorMembership));

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                targetUserId
                        )
        ).thenReturn(Optional.of(targetMembership));

        assertThrows(
                InsufficientOrganisationPermissionException.class,
                () -> organisationMembershipService.removeMember(
                        organisationId,
                        actorUserId,
                        targetUserId
                )
        );

        verify(
                organisationMembershipRepository,
                never()
        ).delete(any(OrganisationMembership.class));
    }

    @Test
    void ownerCannotRemoveOwner() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User ownerUser = User.create(
                "owner-1",
                "Owner",
                "owner@example.com"
        );

        OrganisationMembership ownerMembership =
                OrganisationMembership.create(
                        organisation,
                        ownerUser,
                        OrganisationRole.OWNER
                );

        UUID organisationId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                ownerUserId
                        )
        ).thenReturn(Optional.of(ownerMembership));

        assertThrows(
                InsufficientOrganisationPermissionException.class,
                () -> organisationMembershipService.removeMember(
                        organisationId,
                        ownerUserId,
                        ownerUserId
                )
        );

        verify(
                organisationMembershipRepository,
                never()
        ).delete(any(OrganisationMembership.class));
    }

    @Test
    void engineerCannotRemoveMember() {
        Organisation organisation = Organisation.create(
                "acme",
                "acme"
        );

        User engineerUser = User.create(
                "engineer-id",
                "Engineer",
                "engineer@example.com"
        );

        User viewerUser = User.create(
                "viewer-id",
                "Viewer",
                "viewer@example.com"
        );

        OrganisationMembership engineerMembership =
                OrganisationMembership.create(
                        organisation,
                        engineerUser,
                        OrganisationRole.ENGINEER
                );

        OrganisationMembership viewerMembership =
                OrganisationMembership.create(
                        organisation,
                        viewerUser,
                        OrganisationRole.VIEWER
                );

        UUID organisationId = UUID.randomUUID();
        UUID engineerUserId = UUID.randomUUID();
        UUID viewerUserId = UUID.randomUUID();

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                engineerUserId
                        )
        ).thenReturn(Optional.of(engineerMembership));

        when(
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                viewerUserId
                        )
        ).thenReturn(Optional.of(viewerMembership));

        assertThrows(
                InsufficientOrganisationPermissionException.class,
                () -> organisationMembershipService.removeMember(
                        organisationId,
                        engineerUserId,
                        viewerUserId
                )
        );

        verify(
                organisationMembershipRepository,
                never()
        ).delete(any(OrganisationMembership.class));
    }

    @Test
    void createOrganisationWithOwner_createsOwnerMembership() {
        User owner = User.create(
                "keycloak-user-123",
                "Mohamad",
                "mohamad@example.com"
        );

        Organisation organisation =
                Organisation.create(
                        "Acme",
                        "acme"
                );

        when(
                organisationService.createOrganisation(
                        "Acme",
                        "acme"
                )
        ).thenReturn(organisation);

        when(
                organisationMembershipRepository
                        .save(any(OrganisationMembership.class))
        ).thenAnswer(invocation -> invocation.getArgument(0));

        OrganisationMembership result =
                organisationMembershipService
                        .createOrganisationWithOwner(
                                "Acme",
                                "acme",
                                owner
                        );

        assertSame(organisation, result.getOrganisation());
        assertSame(owner, result.getUser());
        assertEquals(OrganisationRole.OWNER, result.getRole());

        verify(organisationService)
                .createOrganisation("Acme", "acme");

        verify(organisationMembershipRepository)
                .save(any(OrganisationMembership.class));
    }

}