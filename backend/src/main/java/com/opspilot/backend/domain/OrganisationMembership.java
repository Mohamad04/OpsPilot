package com.opspilot.backend.domain;

import com.opspilot.backend.domain.Organisation;
import com.opspilot.backend.domain.OrganisationRole;
import com.opspilot.backend.domain.User;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "organisation_memberships")
public class OrganisationMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrganisationRole role;

    protected OrganisationMembership() {
    }

    public UUID getId() {
        return id;
    }

    public Organisation getOrganisation() {
        return organisation;
    }

    public User getUser() {
        return user;
    }

    public OrganisationRole getRole() {
        return role;
    }

    public void changeRole(OrganisationRole role) {
        this.role = role;
    }

    public static OrganisationMembership create(
            Organisation organisation,
            User user,
            OrganisationRole role
    ) {
        OrganisationMembership membership = new OrganisationMembership();
        membership.organisation = organisation;
        membership.user = user;
        membership.role = role;

        return membership;
    }
}