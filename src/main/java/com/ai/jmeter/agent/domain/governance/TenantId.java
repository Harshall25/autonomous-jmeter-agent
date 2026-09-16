package com.ai.jmeter.agent.domain.governance;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which organization a run belongs to.
 *
 * <p>A validated type rather than a bare string because a tenant id ends up in a filesystem path,
 * and a tenant id is attacker-influenced input in any hosted deployment. Accepting
 * {@code ../../other-tenant} as an id would make cross-tenant reads a matter of typing one, which
 * is why the format is constrained here, once, instead of being sanitized at each use.
 *
 * @param value the canonical, lowercase id
 */
public record TenantId(String value) {

    /** Lowercase alphanumerics and single internal hyphens: a slug, and nothing that is a path. */
    private static final Pattern VALID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    /** The tenant a run belongs to when nobody has configured tenancy at all. */
    private static final String LOCAL = "local";

    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A tenant id must not be blank");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    ("Tenant id '%s' is not a valid slug. Use lowercase letters, digits and "
                            + "hyphens, 1-63 characters, starting and ending alphanumeric.")
                            .formatted(value));
        }
    }

    /** @return the single tenant a local, unauthenticated run belongs to. */
    public static TenantId local() {
        return new TenantId(LOCAL);
    }

    public boolean isLocal() {
        return LOCAL.equals(value);
    }

    /**
     * @param root the shared workspace root
     * @return this tenant's own subdirectory, which the validated format guarantees stays inside
     * {@code root}
     */
    public Path workspaceUnder(Path root) {
        return root.resolve(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
