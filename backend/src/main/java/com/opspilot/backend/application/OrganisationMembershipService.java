package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.InsufficientOrganisationPermissionException;
import com.opspilot.backend.application.exception.OrganisationMembershipAlreadyExistsException;
import com.opspilot.backend.application.exception.OrganisationMembershipNotFoundException;
import com.opspilot.backend.application.exception.UserNotFoundException;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.web.dto.OrganisationResponse;
import com.opspilot.backend.web.dto.OrganisationMemberResponse;
import com.opspilot.backend.web.dto.OrganisationMemberPageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.util.Objects;
import jakarta.persistence.EntityManager;

@Service
public class OrganisationMembershipService {

    private final OrganisationMembershipRepository organisationMembershipRepository;
    private final OrganisationService organisationService;
    private final UserRepository userRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final OrganisationRepository organisationRepository;
    private final MembershipAuditEventRepository membershipAuditEvents;
    private final Clock clock;
    private final EntityManager entityManager;

    public OrganisationMembershipService(
            OrganisationMembershipRepository organisationMembershipRepository,
            OrganisationService organisationService,
            UserRepository userRepository,
            OrganisationRepository organisationRepository,
            AuthenticatedUserService authenticatedUserService,
            MembershipAuditEventRepository membershipAuditEvents,
            Clock clock,
            EntityManager entityManager
    ) {
        this.organisationMembershipRepository =
                organisationMembershipRepository;
        this.organisationService = organisationService;
        this.userRepository = userRepository;
        this.authenticatedUserService = authenticatedUserService;
        this.organisationRepository = organisationRepository;
        this.membershipAuditEvents = membershipAuditEvents;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public OrganisationMemberPageResponse listMembers(String subject, UUID organisationId, int page, int size) {
        User actor = authenticatedUserService.findByIdentityProviderSubject(subject);
        requireMembership(organisationId, actor.getId());
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid member pagination");
        return OrganisationMemberPageResponse.from(organisationMembershipRepository.findByOrganisationId(
                organisationId, PageRequest.of(page, size, Sort.by("id").ascending()))
                .map(OrganisationMemberResponse::from));
    }

    @Transactional
    public OrganisationMemberResponse addMembership(String subject, UUID organisationId, UUID userId, OrganisationRole role) {
        User actor = lockMembershipAdministration(subject, organisationId);
        OrganisationMembership member = addMember(organisationId, actor.getId(), userId, Objects.requireNonNull(role));
        audit(member, actor, MembershipAuditEvent.Action.ADDED, null, role);
        return OrganisationMemberResponse.from(member);
    }

    @Transactional
    public OrganisationMemberResponse updateMembership(String subject, UUID organisationId, UUID membershipId, OrganisationRole role) {
        User actor = lockMembershipAdministration(subject, organisationId);
        OrganisationMembership member = scopedMember(organisationId, membershipId);
        OrganisationRole previous = member.getRole();
        member = changeMemberRole(organisationId, actor.getId(), member.getUser().getId(), Objects.requireNonNull(role));
        if (previous != role) audit(member, actor, MembershipAuditEvent.Action.ROLE_CHANGED, previous, role);
        return OrganisationMemberResponse.from(member);
    }

    @Transactional
    public void deleteMembership(String subject, UUID organisationId, UUID membershipId) {
        User actor = lockMembershipAdministration(subject, organisationId);
        OrganisationMembership member = scopedMember(organisationId, membershipId);
        removeMember(organisationId, actor.getId(), member.getUser().getId());
        // Apply the existing FK/trigger unassignment before appending the receipt, all in this transaction.
        entityManager.flush();
        audit(member, actor, MembershipAuditEvent.Action.REMOVED, member.getRole(), null);
    }

    private OrganisationMembership scopedMember(UUID organisationId, UUID membershipId) {
        return organisationMembershipRepository.findByIdAndOrganisationId(membershipId, organisationId)
                .orElseThrow(() -> new OrganisationMembershipNotFoundException("Organisation membership not found"));
    }

    private User lockMembershipAdministration(String subject, UUID organisationId) {
        User actor = authenticatedUserService.findByIdentityProviderSubject(subject);
        // A projection avoids caching a possibly stale membership before waiting for the parent lock.
        if (!organisationMembershipRepository.existsByOrganisationIdAndUserId(organisationId, actor.getId())) {
            throw new OrganisationMembershipNotFoundException("Organisation membership not found");
        }
        organisationRepository.lockMembershipAdministration(organisationId)
                .orElseThrow(() -> new OrganisationMembershipNotFoundException("Organisation membership not found"));
        // Re-read authorization after acquiring the lock: another admin may have revoked/demoted this actor.
        OrganisationRole role = requireMembership(organisationId, actor.getId()).getRole();
        if (role != OrganisationRole.OWNER && role != OrganisationRole.ADMIN) {
            throw new InsufficientOrganisationPermissionException("Only OWNER and ADMIN can manage organisation members");
        }
        return actor;
    }

    private void audit(OrganisationMembership member, User actor, MembershipAuditEvent.Action action,
                       OrganisationRole previous, OrganisationRole next) {
        membershipAuditEvents.save(MembershipAuditEvent.record(member.getOrganisation().getId(), member.getId(),
                member.getUser().getId(), actor.getId(), action, previous, next, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<OrganisationResponse> getOrganisations(String subject) {
        User user = authenticatedUserService.findByIdentityProviderSubject(subject);
        return organisationMembershipRepository.findAllWithOrganisationByUserId(user.getId())
                .stream()
                .map(this::toOrganisationResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganisationResponse getOrganisation(String subject, UUID organisationId) {
        User user = authenticatedUserService.findByIdentityProviderSubject(subject);
        OrganisationMembership membership = requireMembership(organisationId, user.getId());
        return toOrganisationResponse(membership);
    }

    @Transactional
    public OrganisationResponse createOrganisationWithOwner(String subject, String name, String slug) {
        User owner = authenticatedUserService.findByIdentityProviderSubject(subject);
        OrganisationMembership membership = createOrganisationWithOwner(name, slug, owner);
        return toOrganisationResponse(membership);
    }

    private OrganisationResponse toOrganisationResponse(OrganisationMembership membership) {
        Organisation organisation = membership.getOrganisation();
        return new OrganisationResponse(organisation.getId(), organisation.getName(), organisation.getSlug(),
                organisation.getStatus(), organisation.getCreatedAt(), membership.getUser().getId(), membership.getRole());
    }


    public OrganisationMembership requireMembership(
            UUID organisationId,
            UUID userId
    ) {
        return organisationMembershipRepository
                .findByOrganisationIdAndUserId(
                        organisationId,
                        userId
                )
                .orElseThrow(
                        () -> new OrganisationMembershipNotFoundException(
                                "Organisation membership not found"
                        )
                );
    }

    public void requireMonitoredServiceWritePermission(UUID organisationId, UUID userId) {
        OrganisationMembership membership = requireMembership(organisationId, userId);
        switch (membership.getRole()) {
            case OWNER, ADMIN, ENGINEER -> { }
            case VIEWER -> throw new InsufficientOrganisationPermissionException(
                    "VIEWER cannot modify monitored services"
            );
        }
    }

    public List<OrganisationMembership> getOrganisationMembers(
            UUID organisationId
    ) {
        return organisationMembershipRepository
                .findAllByOrganisationId(organisationId);
    }

    public List<OrganisationMembership> getUserMemberships(
            UUID userId
    ) {
        return organisationMembershipRepository
                .findAllByUserId(userId);
    }

    OrganisationMembership changeMemberRole(
            UUID organisationId,
            UUID actorUserId,
            UUID targetUserId,
            OrganisationRole newRole
    ) {
        OrganisationMembership actor =
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                actorUserId
                        )
                        .orElseThrow(
                                () -> new OrganisationMembershipNotFoundException(
                                        "Actor is not a member of this organisation"
                                )
                        );

        OrganisationMembership target =
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                targetUserId
                        )
                        .orElseThrow(
                                () -> new OrganisationMembershipNotFoundException(
                                        "Target user is not a member of this organisation"
                                )
                        );

        switch (actor.getRole()) {

            case OWNER -> {
                switch (target.getRole()) {

                    case OWNER -> throw new InsufficientOrganisationPermissionException(
                            "OWNER role changes must use an ownership transfer operation"
                    );

                    case ADMIN, ENGINEER, VIEWER -> {
                        if (newRole == OrganisationRole.OWNER) {
                            throw new InsufficientOrganisationPermissionException(
                                    "OWNER role cannot be assigned using a normal role change"
                            );
                        }

                        target.changeRole(newRole);

                        return organisationMembershipRepository.save(target);
                    }
                }
            }

            case ADMIN -> {
                switch (target.getRole()) {

                    case OWNER, ADMIN -> throw new InsufficientOrganisationPermissionException(
                            "ADMIN does not have permission to modify "
                                    + target.getRole()
                    );

                    case ENGINEER, VIEWER -> {
                        if (newRole != OrganisationRole.ENGINEER
                                && newRole != OrganisationRole.VIEWER) {

                            throw new InsufficientOrganisationPermissionException(
                                    "ADMIN cannot assign role " + newRole
                            );
                        }

                        target.changeRole(newRole);

                        return organisationMembershipRepository.save(target);
                    }
                }
            }

            case ENGINEER, VIEWER ->
                    throw new InsufficientOrganisationPermissionException(
                            actor.getRole()
                                    + " does not have permission to manage organisation members"
                    );
        }

        throw new IllegalStateException(
                "Unhandled organisation role: " + actor.getRole()
        );
    }

    void removeMember(
            UUID organisationId,
            UUID actorUserId,
            UUID targetUserId
    ) {
        OrganisationMembership actor =
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                actorUserId
                        )
                        .orElseThrow(
                                () -> new OrganisationMembershipNotFoundException(
                                        "Actor is not a member of this organisation"
                                )
                        );

        OrganisationMembership target =
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                targetUserId
                        )
                        .orElseThrow(
                                () -> new OrganisationMembershipNotFoundException(
                                        "Target user is not a member of this organisation"
                                )
                        );

        switch (actor.getRole()) {
            case OWNER -> {
                if (target.getRole() == OrganisationRole.OWNER) {
                    throw new InsufficientOrganisationPermissionException(
                            "OWNER cannot be removed through normal member removal"
                    );
                }
            }

            case ADMIN -> {
                if (target.getRole() == OrganisationRole.ADMIN
                        || target.getRole() == OrganisationRole.OWNER) {

                    throw new InsufficientOrganisationPermissionException(
                            "ADMIN cannot remove ADMIN or OWNER members"
                    );
                }
            }

            case ENGINEER, VIEWER -> {
                throw new InsufficientOrganisationPermissionException(
                        actor.getRole() + " cannot remove organisation members"
                );
            }
        }

        organisationMembershipRepository.delete(target);
    }

    OrganisationMembership addMember(
            UUID organisationId,
            UUID actorId,
            UUID newMemberId,
            OrganisationRole role
    ) {

        OrganisationMembership actor =
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                                organisationId,
                                actorId
                        )
                        .orElseThrow(
                                () -> new OrganisationMembershipNotFoundException(
                                        "Actor is not a member of this organisation"
                                )
                        );

        switch (actor.getRole()) {
            case OWNER -> {
                if (role == OrganisationRole.OWNER) {
                    throw new InsufficientOrganisationPermissionException(
                            "OWNER role cannot be assigned through normal member creation"
                    );
                }
            }

            case ADMIN -> {
                if (role == OrganisationRole.ADMIN
                        || role == OrganisationRole.OWNER) {
                    throw new InsufficientOrganisationPermissionException(
                            "ADMIN can only assign ENGINEER or VIEWER roles"
                    );
                }
            }

            case ENGINEER, VIEWER -> {
                throw new InsufficientOrganisationPermissionException(
                        actor.getRole() + " cannot add organisation members"
                );
            }
        }

        Organisation organisation =
                organisationService.findById(organisationId);

        User newUser =
                userRepository
                        .findById(newMemberId)
                        .orElseThrow(
                                () -> new UserNotFoundException(
                                        "There is no user with id: " + newMemberId
                                )
                        );

        if (newUser.getStatus() != UserStatus.ACTIVE) {
            throw new UserNotFoundException("User not found");
        }

        if (organisationMembershipRepository
                .findByOrganisationIdAndUserId(
                        organisationId,
                        newMemberId
                )
                .isPresent()) {

            throw new OrganisationMembershipAlreadyExistsException(
                    "User is already a member of this organisation"
            );
        }

        OrganisationMembership newMember =
                OrganisationMembership.create(
                        organisation,
                        newUser,
                        role
                );

        return organisationMembershipRepository.save(newMember);
    }

    @Transactional
    public OrganisationMembership createOrganisationWithOwner(
            String organisationName,
            String organisationSlug,
            User owner
    ) {
        Organisation organisation =
                organisationService.createOrganisation(
                        organisationName,
                        organisationSlug
                );

        OrganisationMembership ownerMembership =
                OrganisationMembership.create(
                        organisation,
                        owner,
                        OrganisationRole.OWNER
                );

        return organisationMembershipRepository.save(ownerMembership);
    }
}
