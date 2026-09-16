package com.ai.jmeter.agent.adapter.api;

import com.ai.jmeter.agent.domain.governance.AccessDeniedException;
import com.ai.jmeter.agent.domain.governance.Permission;
import com.ai.jmeter.agent.domain.governance.Principal;
import com.ai.jmeter.agent.domain.governance.Role;
import com.ai.jmeter.agent.domain.governance.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the identity an authenticating proxy asserted on the request.
 *
 * <p><strong>Only safe behind a gateway that strips these headers from inbound traffic and sets
 * them itself.</strong> This adapter does not authenticate anybody; it trusts what it is told, in
 * the same way an application behind an OIDC-terminating proxy trusts the claims that proxy
 * forwards. Exposed directly to a network, it would let any caller name their own tenant and
 * roles.
 *
 * <p>That is why it refuses to be constructed without explicitly configured header names: an
 * operator has to state that they have a proxy setting these, rather than getting header-based
 * identity by default and finding out later what it meant.
 */
public final class TrustedHeaderPrincipalResolver implements PrincipalResolver {

    private static final Logger log =
            LoggerFactory.getLogger(TrustedHeaderPrincipalResolver.class);

    private final String userHeader;
    private final String tenantHeader;
    private final String rolesHeader;

    public TrustedHeaderPrincipalResolver(
            String userHeader, String tenantHeader, String rolesHeader) {
        if (isBlank(userHeader) || isBlank(tenantHeader) || isBlank(rolesHeader)) {
            throw new IllegalArgumentException(
                    "Header-based identity needs all three header names configured "
                            + "(agent.tenancy.user-header, tenant-header, roles-header). "
                            + "These are only safe behind a gateway that strips them from "
                            + "inbound requests and sets them itself.");
        }
        this.userHeader = userHeader;
        this.tenantHeader = tenantHeader;
        this.rolesHeader = rolesHeader;
    }

    @Override
    public Principal resolve(HttpServletRequest request) {
        String user = request.getHeader(userHeader);
        String tenant = request.getHeader(tenantHeader);

        if (isBlank(user) || isBlank(tenant)) {
            // Fail closed. An unidentified caller is not an anonymous one with default access.
            log.warn("Rejected a control plane call with no asserted identity");
            throw new AccessDeniedException(Permission.VIEW_RUNS);
        }

        try {
            return new Principal(
                    user, new TenantId(tenant), rolesFrom(request.getHeader(rolesHeader)));
        } catch (IllegalArgumentException e) {
            log.warn("Rejected a control plane call with an unusable identity: {}", e.getMessage());
            throw new AccessDeniedException(Permission.VIEW_RUNS);
        }
    }

    /**
     * Parses a comma-separated role list, ignoring names this build does not know.
     *
     * <p>Ignoring rather than failing, because an identity provider that starts issuing a role a
     * newer version understands must not lock everyone out of an older one — and an unknown role
     * grants nothing, so ignoring it is the conservative reading.
     */
    private static Set<Role> rolesFrom(String header) {
        if (isBlank(header)) {
            return Set.of();
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        Arrays.stream(header.split(","))
                .map(name -> name.strip().toUpperCase(Locale.ROOT))
                .filter(name -> !name.isEmpty())
                .forEach(name -> {
                    try {
                        roles.add(Role.valueOf(name));
                    } catch (IllegalArgumentException e) {
                        log.debug("Ignoring unrecognized role '{}'", name);
                    }
                });
        return roles;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
