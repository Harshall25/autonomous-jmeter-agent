package com.ai.jmeter.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Multi-tenancy and run provenance, bound from {@code agent.tenancy.*}.
 *
 * <p>Off by default, and turning it on is a deliberate act: a single operator on a laptop gets a
 * control plane with no ceremony, and a hosted deployment gets tenant isolation, role checks and
 * signed provenance only once someone has configured the identity source and the signing key
 * those depend on.
 *
 * @param enabled      scope the control plane by tenant and enforce roles on every read
 * @param tenant       the tenant this process runs for; each tenant's runs execute in their own
 *                     process and workspace, so a capture can never be read across the boundary
 * @param signingKey   the secret run manifests are signed with; at least 32 characters, and from
 *                     a secret store rather than this file
 * @param promptRevision which revision of the agent's prompts is in force, recorded in provenance
 * @param userHeader   header an authenticating proxy asserts the caller's identity in
 * @param tenantHeader header the same proxy asserts the caller's tenant in
 * @param rolesHeader  header carrying the caller's comma-separated roles
 */
@ConfigurationProperties(prefix = "agent.tenancy")
public record TenancyProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("local") String tenant,
        String signingKey,
        @DefaultValue("") String promptRevision,
        @DefaultValue("X-Auth-Subject") String userHeader,
        @DefaultValue("X-Auth-Tenant") String tenantHeader,
        @DefaultValue("X-Auth-Roles") String rolesHeader) {

    /** @return {@code true} when a key has been supplied to sign run manifests with. */
    public boolean hasSigningKey() {
        return signingKey != null && !signingKey.isBlank();
    }
}
