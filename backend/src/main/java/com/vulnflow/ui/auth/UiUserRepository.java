package com.vulnflow.ui.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiUserRepository extends JpaRepository<UiUser, UUID> {
    Optional<UiUser> findByUsernameIgnoreCase(String username);
}
