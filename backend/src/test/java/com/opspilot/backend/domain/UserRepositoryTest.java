package com.opspilot.backend.domain;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Testcontainers
public class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18.4-alpine");

    private static User createUser(
            String identityProviderSubject,
            String displayName,
            String email
    ) {
        return User.create(
                identityProviderSubject,
                displayName,
                email
        );
    }

    @Test
    void findByIdentityProviderSubject_returnsUserWhenExists() {
        User user = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );

        User savedUser = userRepository.save(user);

        testEntityManager.flush();
        testEntityManager.clear();

        Optional<User> foundUser =
                userRepository.findByIdentityProviderSubject(
                        savedUser.getIdentityProviderSubject()
                );

        assertTrue(foundUser.isPresent());

        assertEquals(
                savedUser.getIdentityProviderSubject(),
                foundUser.get().getIdentityProviderSubject()
        );
    }

    @Test
    void findByIdentityProviderSubject_returnsEmptyWhenUserDoesNotExist() {
        Optional<User> foundUser =
                userRepository.findByIdentityProviderSubject("not-exist");

        assertTrue(foundUser.isEmpty());
    }

    @Test
    void findByEmail_returnsUserWhenExists() {
        User user = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );

        User savedUser = userRepository.save(user);

        testEntityManager.flush();
        testEntityManager.clear();

        Optional<User> foundUser =
                userRepository.findByEmail(savedUser.getEmail());

        assertTrue(foundUser.isPresent());

        assertEquals(
                savedUser.getEmail(),
                foundUser.get().getEmail()
        );
    }

    @Test
    void findByEmail_returnsEmptyWhenUserDoesNotExist() {
        Optional<User> foundUser =
                userRepository.findByEmail("not-exist");

        assertTrue(foundUser.isEmpty());
    }

    @Test
    void duplicateIdentityProviderSubject_throwsException() {
        User user1 = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );

        userRepository.save(user1);
        testEntityManager.flush();

        User user2 = createUser(
                "identity-123",
                "Another User",
                "another@example.com"
        );

        userRepository.save(user2);

        assertThrows(
                ConstraintViolationException.class,
                () -> testEntityManager.flush()
        );
    }

    @Test
    void duplicateEmail_throwsException() {
        User user1 = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );

        userRepository.save(user1);
        testEntityManager.flush();

        User user2 = createUser(
                "identity-456",
                "Another User",
                "mohamad@example.com"
        );

        userRepository.save(user2);

        assertThrows(
                ConstraintViolationException.class,
                () -> testEntityManager.flush()
        );
    }
}