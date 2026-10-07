package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.UserNotFoundException;
import com.opspilot.backend.domain.User;
import com.opspilot.backend.domain.UserRepository;
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
                .orElseThrow(
                        () -> new UserNotFoundException(
                                "No OpsPilot user found for authenticated identity"
                        ));
    }

}