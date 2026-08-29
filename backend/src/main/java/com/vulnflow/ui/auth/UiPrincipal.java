package com.vulnflow.ui.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record UiPrincipal(UUID id, String username, UiRole role, boolean passwordChangeRequired)
        implements Principal, Serializable {

    @Override
    public String getName() {
        return username;
    }
}
