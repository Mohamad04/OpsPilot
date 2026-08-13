package com.opspilot.backend.domain;

import com.opspilot.backend.domain.exception.InvalidOrganisationNameException;
import com.opspilot.backend.domain.exception.InvalidOrganisationSlugException;
import com.opspilot.backend.domain.exception.OrganisationAlreadyInStatusException;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "organisations")
public class Organisation {

    private static final int NAME_MAX_LENGTH = 100;
    private static final int SLUG_MIN_LENGTH = 2;
    private static final int SLUG_MAX_LENGTH = 100;
    private static final String SLUG_PATTERN =
            "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @NotBlank
    @Size(max = NAME_MAX_LENGTH)
    private String name;
    @NotBlank
    @Size(min = SLUG_MIN_LENGTH ,max = SLUG_MAX_LENGTH)
    @Pattern(regexp = SLUG_PATTERN)
    private String slug;
    @NotNull
    @Enumerated(EnumType.STRING)
    private OrganisationStatus status;
    @NotNull
    @Column(name = "created_at")
    private Instant createdAt;

    protected Organisation() {

    }
    private static void validateName(String name,int max) {
        if(name == null || name.isBlank()) {
            throw new InvalidOrganisationNameException("name cannot be null or blank");
        }
        if(name.length() > max) {
            throw new InvalidOrganisationNameException("name cannot be longer than %d characters".formatted(max));
        }
    }

    private static void validateSlug(String slug,int max, int min) {
        if(slug == null || slug.isBlank()) {
            throw new InvalidOrganisationSlugException("slug cannot be null or blank");
        }
        if(slug.length() > max) {
            throw new InvalidOrganisationSlugException("slug cannot be longer than %d characters".formatted(max));
        }
        if(slug.length() < min) {
            throw new InvalidOrganisationSlugException("slug cannot be shorter than %d  characters".formatted(min));
        }
        if(!slug.matches(SLUG_PATTERN)) {
            throw new InvalidOrganisationSlugException("slug has invalid format.");
        }
    }
    private Organisation(String name, String slug) {
        validateName(name,NAME_MAX_LENGTH);
        validateSlug(slug,SLUG_MAX_LENGTH,SLUG_MIN_LENGTH);

        this.name = name;
        this.slug = slug;
        this.status = OrganisationStatus.INACTIVE;
        this.createdAt = Instant.now();
    }
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
    void rename(String name) {
        validateName(name,NAME_MAX_LENGTH);
        this.name = name;
    }
    public String getSlug() {
        return slug;
    }
    void changeSlug(String slug) {
        validateSlug(slug,SLUG_MAX_LENGTH,SLUG_MIN_LENGTH);
        this.slug = slug;
    }
    public  OrganisationStatus getStatus() {
        return status;
    }
    void activate() {
        if(this.status == OrganisationStatus.ACTIVE) {
            throw new OrganisationAlreadyInStatusException(OrganisationStatus.ACTIVE);
        }
        this.status = OrganisationStatus.ACTIVE;
    }
    void deactivate() {
        if(this.status == OrganisationStatus.INACTIVE) {
            throw new OrganisationAlreadyInStatusException(OrganisationStatus.INACTIVE);
        }
        this.status = OrganisationStatus.INACTIVE;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Organisation create(String name , String slug) {
        return new Organisation(name,slug);
    }

    @Override
    public String toString() {
        return String.format("Organisation[id=%s, name='%s', slug='%s', " +
                "status='%s', created_at='%s']",id,name,slug,status,createdAt);
    }
}