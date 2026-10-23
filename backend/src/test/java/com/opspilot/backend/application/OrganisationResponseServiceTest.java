package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.OrganisationMembershipNotFoundException;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.web.dto.OrganisationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class OrganisationResponseServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORGANISATION_ID = UUID.randomUUID();
    @Mock OrganisationMembershipRepository membershipRepository;
    @Mock OrganisationService organisationService;
    @Mock UserRepository userRepository;
    @Mock OrganisationRepository organisationRepository;
    @Mock AuthenticatedUserService authenticatedUserService;
    @InjectMocks OrganisationMembershipService service;
    private User user;
    private Organisation organisation;

    @BeforeEach
    void authenticatedUser() {
        user = mock(User.class);
        lenient().when(user.getId()).thenReturn(USER_ID);
        when(authenticatedUserService.findByIdentityProviderSubject("subject")).thenReturn(user);
        organisation = Organisation.create("Acme", "acme");
    }

    @Test
    void listMapsOnlyAuthenticatedUsersFetchedOrganisations() {
        when(membershipRepository.findAllWithOrganisationByUserId(USER_ID)).thenReturn(List.of(
                OrganisationMembership.create(organisation, user, OrganisationRole.VIEWER)));
        List<OrganisationResponse> result = service.getOrganisations("subject");
        assertEquals(1, result.size());
        assertEquals("Acme", result.getFirst().name());
        assertEquals("acme", result.getFirst().slug());
        assertEquals(OrganisationStatus.INACTIVE, result.getFirst().status());
        assertEquals(organisation.getCreatedAt(), result.getFirst().createdAt());
        verify(membershipRepository).findAllWithOrganisationByUserId(USER_ID);
    }

    @Test
    void detailRequiresMembershipBeforeMappingOrganisation() {
        when(membershipRepository.findByOrganisationIdAndUserId(ORGANISATION_ID, USER_ID)).thenReturn(Optional.of(
                OrganisationMembership.create(organisation, user, OrganisationRole.VIEWER)));
        assertEquals("Acme", service.getOrganisation("subject", ORGANISATION_ID).name());
        verify(membershipRepository).findByOrganisationIdAndUserId(ORGANISATION_ID, USER_ID);
        verifyNoInteractions(organisationService);
    }

    @Test
    void foreignOrganisationIsHidden() {
        when(membershipRepository.findByOrganisationIdAndUserId(ORGANISATION_ID, USER_ID))
                .thenReturn(Optional.empty());
        assertThrows(OrganisationMembershipNotFoundException.class,
                () -> service.getOrganisation("subject", ORGANISATION_ID));
        verifyNoInteractions(organisationService);
    }

    @Test
    void createMapsDtoAfterCreatingAuthenticatedOwnersMembership() {
        when(organisationService.createOrganisation("Acme", "acme")).thenReturn(organisation);
        when(membershipRepository.save(any(OrganisationMembership.class))).thenAnswer(call -> call.getArgument(0));
        OrganisationResponse result = service.createOrganisationWithOwner("subject", "Acme", "acme");
        assertEquals("Acme", result.name());
        ArgumentCaptor<OrganisationMembership> membership = ArgumentCaptor.forClass(OrganisationMembership.class);
        verify(membershipRepository).save(membership.capture());
        assertSame(user, membership.getValue().getUser());
        assertSame(organisation, membership.getValue().getOrganisation());
        assertEquals(OrganisationRole.OWNER, membership.getValue().getRole());
    }
}
