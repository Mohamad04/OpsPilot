package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.ForbiddenException;
import com.opspilot.backend.application.exception.UserProvisioningConflictException;
import com.opspilot.backend.domain.User;
import com.opspilot.backend.domain.UserRepository;
import com.opspilot.backend.web.dto.UserResponse;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Objects;

@Service
public class UserProvisioningService {
    private final UserRepository users;
    private final AuthenticatedUserService authenticatedUsers;
    private final Validator validator;
    private final TransactionTemplate transaction;

    public UserProvisioningService(UserRepository users, AuthenticatedUserService authenticatedUsers,
                                   Validator validator, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.authenticatedUsers = authenticatedUsers;
        this.validator = validator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Arguments come exclusively from the controller's validated security principal.
    public UserResponse provision(String subject, String email, boolean emailVerified,
                                  String name, String preferredUsername) {
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new ForbiddenException("A valid authenticated identity is required.");
        }
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                User existing = users.findByIdentityProviderSubject(subject).orElse(null);
                if (existing != null) return response(existing);

                String normalizedEmail = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
                if (!emailVerified || !validator.validate(new EmailClaim(normalizedEmail)).isEmpty()) {
                    throw new ForbiddenException("A verified, valid Keycloak email address is required to create your OpsPilot account.");
                }
                // Never attach a new subject to an account found by email.
                if (users.existsByEmailIgnoreCase(normalizedEmail)) {
                    // Another request for this subject may have committed since our first read.
                    return users.findByIdentityProviderSubject(subject).map(this::response)
                            .orElseThrow(UserProvisioningConflictException::new);
                }

                String displayName = firstNonBlank(name, preferredUsername, "OpsPilot user");
                User created = users.save(User.create(subject, displayName, normalizedEmail));
                return response(created);
            }));
        } catch (DataIntegrityViolationException exception) {
            if (!isIdentityUniquenessConflict(exception)) throw exception;
            // The failed insert must roll back before looking for the concurrent winner.
            // Database uniqueness also protects requests handled by different app instances.
            return Objects.requireNonNull(transaction.execute(status -> users.findByIdentityProviderSubject(subject)
                    .map(this::response)
                    .orElseThrow(UserProvisioningConflictException::new)));
        }
    }

    private UserResponse response(User user) {
        authenticatedUsers.requireActive(user);
        return new UserResponse(user.getId(), user.getDisplayName());
    }

    private static String firstNonBlank(String name, String username, String fallback) {
        String value = name != null && !name.isBlank() ? name.strip()
                : username != null && !username.isBlank() ? username.strip() : fallback;
        // Match the existing VARCHAR(100) constraint without splitting a Unicode code point.
        return value.substring(0, value.offsetByCodePoints(0, Math.min(100, value.codePointCount(0, value.length()))));
    }

    private static boolean isIdentityUniquenessConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uk_users_identity_provider_subject".equals(violation.getConstraintName())
                        || "users_email_key".equals(violation.getConstraintName());
            }
        }
        return false;
    }

    private record EmailClaim(@NotBlank @Email @Size(max = 255) String value) { }
}
