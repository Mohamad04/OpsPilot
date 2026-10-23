package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.UserNotFoundException;
import com.opspilot.backend.application.exception.ForbiddenException;
import com.opspilot.backend.domain.User;
import com.opspilot.backend.domain.UserRepository;
import com.opspilot.backend.domain.UserStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedUserService {

    private final UserRepository userRepository;

    public AuthenticatedUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User findByIdentityProviderSubject(String subject){
        return userRepository
                .findByIdentityProviderSubject(
                        subject
                )
                .map(this::requireActive)
                .orElseThrow(
                        () -> new UserNotFoundException(
                                "No OpsPilot user found for authenticated identity"
                        ));
    }

    public User requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Your OpsPilot account is inactive. Contact an administrator.");
        }
        return user;
    }

}
