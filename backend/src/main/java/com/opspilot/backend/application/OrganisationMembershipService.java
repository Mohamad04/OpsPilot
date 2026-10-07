package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.InsufficientOrganisationPermissionException;
import com.opspilot.backend.application.exception.OrganisationMembershipAlreadyExistsException;
import com.opspilot.backend.application.exception.OrganisationMembershipNotFoundException;
import com.opspilot.backend.application.exception.UserNotFoundException;
import com.opspilot.backend.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrganisationMembershipService {

    private final OrganisationMembershipRepository organisationMembershipRepository;
    private final OrganisationService organisationService;
    private final UserRepository userRepository;

    public OrganisationMembershipService(
            OrganisationMembershipRepository organisationMembershipRepository,
            OrganisationService organisationService,
            UserRepository userRepository,
            OrganisationRepository organisationRepository
    ) {
        this.organisationMembershipRepository =
                organisationMembershipRepository;
        this.organisationService = organisationService;
        this.userRepository = userRepository;
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

    public OrganisationMembership changeMemberRole(
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

    public void removeMember(
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

    public OrganisationMembership addMember(
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