package com.opspilot.backend.domain;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "identity_provider_subject", nullable = false, unique = true)
    private String identityProviderSubject;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    protected User() {
    }

    public UUID getId() {
        return id;
    }

    public String getIdentityProviderSubject() {
        return identityProviderSubject;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public UserStatus getStatus() {
        return status;
    }

    public static User create(
            String identityProviderSubject,
            String displayName,
            String email
    ) {
        User user = new User();
        user.identityProviderSubject = identityProviderSubject;
        user.displayName = displayName;
        user.email = email;
        user.status = UserStatus.ACTIVE;

        return user;
    }

}