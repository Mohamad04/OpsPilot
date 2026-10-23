package com.opspilot.backend.web;

import com.opspilot.backend.application.UserProvisioningService;
import com.opspilot.backend.web.dto.UserResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    private final UserProvisioningService userProvisioningService;

    public MeController(UserProvisioningService userProvisioningService) {
        this.userProvisioningService = userProvisioningService;
    }

    @PostMapping("/api/me/provision")
    public UserResponse provision(@AuthenticationPrincipal Jwt jwt) {
        return userProvisioningService.provision(stringClaim(jwt, "sub"), stringClaim(jwt, "email"),
                Boolean.TRUE.equals(jwt.getClaims().get("email_verified")),
                stringClaim(jwt, "name"), stringClaim(jwt, "preferred_username"));
    }

    private static String stringClaim(Jwt jwt, String claim) {
        Object value = jwt.getClaims().get(claim);
        return value instanceof String text ? text : null;
    }
}
