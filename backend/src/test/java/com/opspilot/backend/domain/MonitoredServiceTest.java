package com.opspilot.backend.domain;

import com.opspilot.backend.domain.exception.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MonitoredServiceTest {
    private static MonitoredService createValidMonitoredService(Organisation organisation) {
        return MonitoredService.create(
                organisation, "Payment API", ServiceType.HTTP,
                Environment.DEVELOPMENT, "https://payment.example.com",
                "/health", "Payments Team"
        );
    }

    @Test
    void create_whenArgumentsAreValid_createsEnabledMonitoredService(){
        Organisation organisation = Organisation.create("acme"
        ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        assertEquals("Payment API", monitoredService.getName());
        assertEquals("acme", monitoredService.getOrganisation().getName());
        assertEquals("acme", monitoredService.getOrganisation().getSlug());
        assertSame(organisation, monitoredService.getOrganisation());
        assertEquals(Environment.DEVELOPMENT, monitoredService.getEnvironment());
        assertEquals(ServiceType.HTTP, monitoredService.getServiceType());
        assertEquals("https://payment.example.com", monitoredService.getBaseUrl());
        assertEquals("/health", monitoredService.getHealthEndpoint());
        assertEquals("Payments Team",monitoredService.getOwner());
        assertTrue(monitoredService.isEnabled());
        assertNull(monitoredService.getId());
    }
    @Test
    void create_whenNameIsNull_throwsInvalidMonitoredServiceNameException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceNameException.class,
                () ->MonitoredService.create(
                organisation,null, ServiceType.HTTP,
                Environment.DEVELOPMENT,"https://payment.example.com",
                "/health","Payments Team"
        ));

    }
    @Test
    void create_whenNameIsBlank_throwsInvalidMonitoredServiceNameException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceNameException.class,
                () ->MonitoredService.create(
                        organisation,"", ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "/health","Payments Team"
                ));
    }
    @Test
    void create_whenNameExceedsMaxLength_throwsInvalidMonitoredServiceNameException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        String name = "a".repeat(MonitoredService.NAME_MAX_LENGTH + 1);
        assertThrows(InvalidMonitoredServiceNameException.class,
                () ->MonitoredService.create(
                        organisation,name, ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenBaseUrlIsNull_throwsInvalidMonitoredServiceBaseUrlException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceBaseUrlException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT,null,
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenBaseUrlIsBlank_throwsInvalidMonitoredServiceBaseUrlException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceBaseUrlException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT," ",
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenBaseUrlExceedsMaxLength_throwsInvalidMonitoredServiceBaseUrlException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        String url = "a".repeat(MonitoredService.BASE_URL_MAX_LENGTH + 1);
        assertThrows(InvalidMonitoredServiceBaseUrlException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT,url,
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenBaseUrlUsesUnsupportedScheme_throwsInvalidMonitoredServiceBaseUrlException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceBaseUrlException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT,"ftp://payment.example.com",
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenBaseUrlHasNoHost_throwsInvalidMonitoredServiceBaseUrlException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceBaseUrlException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https:/payment.com",
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenBaseUrlIsMalformed_throwsInvalidMonitoredServiceBaseUrlException(){
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceBaseUrlException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment example.com",
                        "/health","Payments Team"
                ));
    }
    @Test
    void create_whenHealthEndpointIsNull_throwsInvalidMonitoredServiceHealthEndpointException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceHealthEndpointException.class,
                () ->MonitoredService.create(
                        organisation,"Payment API",ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        null,"Payments Team"
                ));
    }

    @Test
    void create_whenHealthEndpointIsBlank_throwsInvalidMonitoredServiceHealthEndpointException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceHealthEndpointException.class,
                () ->MonitoredService.create(
                        organisation,"Payment API",ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "   ","Payments Team"
                ));
    }

    @Test
    void create_whenHealthEndpointExceedsMaxLength_throwsInvalidMonitoredServiceHealthEndpointException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        String healthEndpoint = "a".repeat(MonitoredService.HEALTH_ENDPOINT_MAX_LENGTH + 1);
        assertThrows(InvalidMonitoredServiceHealthEndpointException.class,
                () ->MonitoredService.create(
                        organisation,"Payment API",ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        healthEndpoint,"Payments Team"
                ));
    }

    @Test
    void create_whenHealthEndpointDoesNotStartWithSlash_throwsInvalidMonitoredServiceHealthEndpointException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceHealthEndpointException.class,
                () ->MonitoredService.create(
                        organisation,"Payment API",ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "health","Payments Team"
                ));
    }

    @Test
    void create_whenHealthEndpointStartsWithDoubleSlash_throwsInvalidMonitoredServiceHealthEndpointException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceHealthEndpointException.class,
                () ->MonitoredService.create(
                        organisation,"Payment API",ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "//health","Payments Team"
                ));
    }

    @Test
    void create_whenOrganisationIsNull_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () ->MonitoredService.create(
                        null,"Payment API",ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenServiceTypeIsNull_throwsIllegalArgumentException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(IllegalArgumentException.class,
                () ->MonitoredService.create(
                        organisation,"test", null,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenEnvironmentIsNull_throwsIllegalArgumentException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(IllegalArgumentException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        null,"https://payment.example.com",
                        "/health","Payments Team"
                ));
    }

    @Test
    void create_whenOwnerIsNull_throwsInvalidMonitoredServiceOwnerException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceOwnerException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "/health",null
                ));
    }

    @Test
    void create_whenOwnerIsBlank_throwsInvalidMonitoredServiceOwnerException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        assertThrows(InvalidMonitoredServiceOwnerException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "/health","   "
                ));
    }

    @Test
    void create_whenOwnerExceedsMaxLength_throwsInvalidMonitoredServiceOwnerException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        String owner = "a".repeat(MonitoredService.OWNER_MAX_LENGTH+1);
        assertThrows(InvalidMonitoredServiceOwnerException.class,
                () ->MonitoredService.create(
                        organisation,"test", ServiceType.HTTP,
                        Environment.DEVELOPMENT,"https://payment.example.com",
                        "/health",owner
                ));
    }

    @Test
    void rename_whenNameIsValid_changesName() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.rename("New Payment API");
        assertEquals("New Payment API", monitoredService.getName());
    }

    @Test
    void rename_whenNameIsInvalid_throwsInvalidMonitoredServiceNameException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        assertThrows(InvalidMonitoredServiceNameException.class,
                () -> monitoredService.rename("   "));
        assertEquals("Payment API", monitoredService.getName());
    }

    @Test
    void changeBaseUrl_whenUrlIsValid_changesBaseUrl() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.changeBaseUrl("https://payment.example2.com");
        assertEquals("https://payment.example2.com", monitoredService.getBaseUrl());
    }

    @Test
    void changeBaseUrl_whenUrlIsInvalid_throwsInvalidMonitoredServiceBaseUrlException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        assertThrows(InvalidMonitoredServiceBaseUrlException.class
        ,() -> monitoredService.changeBaseUrl("ftp://payment.example2.com"));
        assertEquals("https://payment.example.com", monitoredService.getBaseUrl());
    }

    @Test
    void disable_whenEnabled_disablesService() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.disable();
        assertFalse(monitoredService.isEnabled());
    }

    @Test
    void disable_whenAlreadyDisabled_throwsMonitoredServiceAlreadyInStatusException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.disable();
        assertThrows(MonitoredServiceAlreadyInStatusException.class,
                () -> monitoredService.disable());
        assertFalse(monitoredService.isEnabled());
    }

    @Test
    void enable_whenDisabled_enablesService() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.disable();
        monitoredService.enable();
        assertTrue(monitoredService.isEnabled());
    }

    @Test
    void enable_whenAlreadyEnabled_throwsMonitoredServiceAlreadyInStatusException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        assertThrows(MonitoredServiceAlreadyInStatusException.class,
                () -> monitoredService.enable());
        assertTrue(monitoredService.isEnabled());
    }

    @Test
    void changeHealthEndpoint_whenEndpointIsValid_changesHealthEndpoint() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.changeHealthEndpoint("/health2");
        assertEquals("/health2", monitoredService.getHealthEndpoint());
    }

    @Test
    void changeHealthEndpoint_whenEndpointIsInvalid_throwsInvalidMonitoredServiceHealthEndpointException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        assertThrows(InvalidMonitoredServiceHealthEndpointException.class,
                () -> monitoredService.changeHealthEndpoint("health"));
        assertEquals("/health", monitoredService.getHealthEndpoint());
    }

    @Test
    void changeOwner_whenOwnerIsValid_changesOwner() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.changeOwner("Me");
        assertEquals("Me", monitoredService.getOwner());
    }

    @Test
    void changeOwner_whenOwnerIsInvalid_throwsInvalidMonitoredServiceOwnerException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        assertThrows(InvalidMonitoredServiceOwnerException.class,
                () -> monitoredService.changeOwner("   "));
        assertEquals("Payments Team", monitoredService.getOwner());
    }

    @Test
    void changeServiceType_whenServiceTypeIsValid_changesServiceType() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.changeServiceType(ServiceType.TCP);
        assertEquals(ServiceType.TCP, monitoredService.getServiceType());

    }

    @Test
    void changeServiceType_whenServiceTypeIsNull_throwsIllegalArgumentException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        assertThrows(IllegalArgumentException.class, () -> monitoredService.changeServiceType(null));
        assertEquals(ServiceType.HTTP, monitoredService.getServiceType());
    }

    @Test
    void changeEnvironment_whenEnvironmentIsValid_changesEnvironment() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        monitoredService.changeEnvironment(Environment.TEST);
        assertEquals(Environment.TEST, monitoredService.getEnvironment());

    }

    @Test
    void changeEnvironment_whenEnvironmentIsNull_throwsIllegalArgumentException() {
        Organisation organisation = Organisation.create("acme"
                ,"acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);
        assertThrows(IllegalArgumentException.class, () -> monitoredService.changeEnvironment(null));
        assertEquals(Environment.DEVELOPMENT, monitoredService.getEnvironment());
    }
}

