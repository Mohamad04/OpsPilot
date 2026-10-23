package com.opspilot.backend.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.opspilot.backend.application.OrganisationMembershipService;
import com.opspilot.backend.application.UserProvisioningService;
import com.opspilot.backend.web.dto.UserResponse;
import com.opspilot.backend.config.SecurityConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Real resource-server decoding and Boot audience configuration; no mocked JwtDecoder.
@WebMvcTest({OrganisationController.class, MeController.class})
@Import(SecurityConfig.class)
class JwtAuthenticationTest {
    private static final String ISSUER = "https://issuer.example/realms/opspilot";
    private static final String AUDIENCE = "opspilot-backend";
    private static final KeyPair KEYS = keyPair();
    private static final HttpServer JWKS_SERVER = jwksServer();

    @Autowired MockMvc mockMvc;
    @MockitoBean OrganisationMembershipService memberships;
    @MockitoBean UserProvisioningService provisioning;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopServer() {
        JWKS_SERVER.stop(0);
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer jwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            RSAKey publicKey = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID("test-key").build();
            byte[] body = new JWKSet(publicKey).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String token(String issuer, List<String> audience, Instant expiration, KeyPair signingKeys) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("keycloak-subject").issuer(issuer)
                .audience(audience).issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiration)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) signingKeys.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void signedTokenForThisIssuerAndApiResolvesSubject() throws Exception {
        when(memberships.getOrganisations("keycloak-subject")).thenReturn(List.of());
        mockMvc.perform(get("/api/organisations").header("Authorization", "Bearer "
                        + token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), KEYS)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(memberships).getOrganisations("keycloak-subject");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-issuer", "wrong-audience", "missing-audience", "expired", "wrong-signature"})
    void rejectsInvalidClaimsAndSignaturesBeforeMembershipLookup(String invalid) throws Exception {
        String issuer = invalid.equals("wrong-issuer") ? "https://untrusted.example" : ISSUER;
        List<String> audience = switch (invalid) {
            case "wrong-audience" -> List.of("another-api");
            case "missing-audience" -> List.of();
            default -> List.of(AUDIENCE);
        };
        Instant expiration = Instant.now().plusSeconds(invalid.equals("expired") ? -300 : 300);
        mockMvc.perform(get("/api/organisations").header("Authorization", "Bearer "
                        + token(issuer, audience, expiration, invalid.equals("wrong-signature") ? keyPair() : KEYS)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
        verifyNoInteractions(memberships);
        mockMvc.perform(post("/api/me/provision").header("Authorization", "Bearer "
                        + token(issuer, audience, expiration, invalid.equals("wrong-signature") ? keyPair() : KEYS)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(provisioning);
    }

    @Test
    void provisioningRejectsMissingAndMalformedBearerTokens() throws Exception {
        mockMvc.perform(post("/api/me/provision")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/provision").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(provisioning);
    }

    @Test
    void provisioningUsesTheValidatedJwtSubject() throws Exception {
        UserResponse user = new UserResponse(java.util.UUID.randomUUID(), "Existing user");
        when(provisioning.provision("keycloak-subject", null, false, null, null)).thenReturn(user);
        mockMvc.perform(post("/api/me/provision").header("Authorization", "Bearer "
                        + token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), KEYS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.id().toString()));
        verify(provisioning).provision("keycloak-subject", null, false, null, null);
    }

    @Test
    void allowsFrontendPreflightWithAuthorizationAndContentType() throws Exception {
        mockMvc.perform(options("/api/organisations")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().exists("Access-Control-Allow-Headers"));
        verifyNoInteractions(memberships);
    }

    @Test
    void rejectsUntrustedCorsOrigin() throws Exception {
        mockMvc.perform(options("/api/organisations")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(memberships);
    }
}
