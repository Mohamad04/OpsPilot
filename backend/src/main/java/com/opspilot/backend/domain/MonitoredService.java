package com.opspilot.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

@Entity
@Table(name= "monitored_services")
public class MonitoredService {

    public static final int NAME_MAX_LENGTH = 120;
    public static final int BASE_URL_MAX_LENGTH = 2048;
    public static final int HEALTH_ENDPOINT_MAX_LENGTH = 2048;
    public static final int OWNER_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY,optional = false)
    @JoinColumn(name="organisation_id", nullable=false)
    private Organisation organisation;
    @NotBlank
    @Size(max=NAME_MAX_LENGTH)
    @Column(nullable=false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "service_type",nullable=false)
    @NotNull
    private ServiceType serviceType;
    @Enumerated(EnumType.STRING)
    @Column(name = "environment",nullable=false)
    @NotNull
    private Environment environment;
    @NotBlank
    @Size(max=BASE_URL_MAX_LENGTH)
    @Column(name= "base_url", nullable=false)
    private String baseUrl;
    @NotBlank
    @Size(max = HEALTH_ENDPOINT_MAX_LENGTH)
    @Column(name = "health_endpoint", nullable = false)
    private String healthEndpoint;

    @NotBlank
    @Size(max = OWNER_MAX_LENGTH)
    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private boolean enabled;

    protected MonitoredService() {
        //jpa
    }
    private MonitoredService(Organisation organisation,
                             String name,
                             ServiceType serviceType,
                             Environment environment,
                             String baseUrl,
                             String healthEndpoint,
                             String owner)
    {
        validateOrganisation(organisation);
        validateName(name);
        validateServiceType(serviceType);
        validateEnvironment(environment);
        validateBaseUrl(baseUrl);
        validateHealthEndpoint(healthEndpoint);
        validateOwner(owner);

        this.organisation = organisation;
        this.name = name;
        this.serviceType = serviceType;
        this.environment = environment;
        this.baseUrl = baseUrl;
        this.healthEndpoint = healthEndpoint;
        this.owner = owner;
        this.enabled = true;
    }

    public static MonitoredService create(
            Organisation organisation,
            String name,
            ServiceType serviceType,
            Environment environment,
            String baseUrl,
            String healthEndpoint,
            String owner
            ){
        return new MonitoredService(organisation,
                name, serviceType, environment,
                baseUrl, healthEndpoint, owner);
    }

    public String getName() {
        return name;
    }
    public ServiceType getServiceType() {
        return serviceType;
    }
    public String getBaseUrl() {
        return baseUrl;
    }
    public String getHealthEndpoint() {
        return healthEndpoint;
    }
    public String getOwner() {
        return owner;
    }
    public boolean isEnabled() {
        return enabled;
    }
    public Organisation getOrganisation() {
        return organisation;
    }
    public Environment getEnvironment() {
        return environment;
    }
    public UUID getId() {
        return id;
    }
    public void rename(String newName) {
        validateName(newName);
        this.name = newName;
    }
    public void changeServiceType(ServiceType serviceType) {
        validateServiceType(serviceType);
        this.serviceType = serviceType;
    }
    public void changeEnvironment(Environment environment) {
        validateEnvironment(environment);
        this.environment = environment;
    }
    public void changeBaseUrl(String baseUrl) {
        validateBaseUrl(baseUrl);
        this.baseUrl = baseUrl;
    }
    public void changeHealthEndpoint(String healthEndpoint) {
        validateHealthEndpoint(healthEndpoint);
        this.healthEndpoint = healthEndpoint;
    }
    public void changeOwner(String owner) {
        validateOwner(owner);
        this.owner = owner;
    }
    public void enable() {
        if(this.enabled) {
            throw new IllegalStateException("Monitored service is already enabled.");
        }
        this.enabled = true;
    }
    public void disable() {
        if(!this.enabled) {
            throw new IllegalStateException("Monitored service is not enabled.");
        }
        this.enabled = false;
    }

    private static void validateName(String name){
        if(name == null || name.isBlank()){
            throw new IllegalArgumentException("Name cannot be null or blank.");
        }
        if(name.length() > NAME_MAX_LENGTH){
            throw new IllegalArgumentException("Name cannot exceed %d characters.".formatted(NAME_MAX_LENGTH));
        }
    }
    private static void validateServiceType(ServiceType serviceType){
        if(serviceType == null){
            throw new IllegalArgumentException("Service type cannot be null.");
        }
    }
    private static void validateEnvironment(Environment environment){
        if(environment == null){
            throw new IllegalArgumentException("Environment cannot be null.");
        }
    }
    private static void validateOrganisation(Organisation organisation){
        if(organisation == null){
            throw new IllegalArgumentException("Organisation cannot be null.");
        }
    }
    private static void validateBaseUrl(String baseUrl){
        if(baseUrl == null || baseUrl.isBlank()){
            throw new IllegalArgumentException("Base URL cannot be null or blank.");
        }
        if(baseUrl.length() > BASE_URL_MAX_LENGTH){
            throw new IllegalArgumentException("Base URL cannot exceed %d characters.".formatted(BASE_URL_MAX_LENGTH));
        }
        try {
            URI uri = new URI(baseUrl);

            String scheme = uri.getScheme();

            if (scheme == null ||
                    !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException(
                        "Base URL must use HTTP or HTTPS."
                );
            }

            if (uri.getHost() == null) {
                throw new IllegalArgumentException(
                        "Base URL must contain a valid host."
                );
            }

        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "Base URL is not valid."
            );
        }
    }
    private static void validateHealthEndpoint(String healthEndpoint){
        if(healthEndpoint == null || healthEndpoint.isBlank()){
            throw new IllegalArgumentException("Health Endpoint cannot be null or blank.");
        }
        if(healthEndpoint.length() > HEALTH_ENDPOINT_MAX_LENGTH){
            throw new IllegalArgumentException("Health Endpoint cannot exceed %d characters.".formatted(HEALTH_ENDPOINT_MAX_LENGTH));
        }
        if (!healthEndpoint.startsWith("/") || healthEndpoint.startsWith("//")) {
            throw new IllegalArgumentException(
                    "Health endpoint must be an absolute path starting with a single '/'."
            );
        }
        }
    private static void validateOwner(String owner){
        if(owner == null || owner.isBlank()){
            throw new IllegalArgumentException("Owner cannot be null or blank.");
        }
        if(owner.length() > OWNER_MAX_LENGTH){
            throw new IllegalArgumentException("Owner cannot exceed %d characters.".formatted(OWNER_MAX_LENGTH));
        }
    }

}
