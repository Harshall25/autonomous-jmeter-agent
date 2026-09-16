package com.ai.jmeter.agent.domain.governance;

import java.util.Set;

/**
 * A named bundle of permissions, as an organization actually assigns them.
 *
 * <p>Deliberately not hierarchical. An approver in a regulated environment is often expressly not
 * an operator — the whole point of the separation is that the person who attests to a plan is not
 * the person who produced it — and a role model where every higher role subsumes the one below it
 * cannot express that.
 */
public enum Role {

    /** Reads results. The default for everyone who is not doing the testing. */
    VIEWER(Set.of(Permission.VIEW_RUNS)),

    /** Runs the agent and reviews what it changed. */
    OPERATOR(Set.of(Permission.VIEW_RUNS, Permission.VIEW_HEAL_DIFFS, Permission.START_RUN)),

    /** Attests to a plan without being able to produce one. */
    APPROVER(Set.of(Permission.VIEW_RUNS, Permission.VIEW_HEAL_DIFFS, Permission.APPROVE_PLAN)),

    /** Administers the tenant. */
    ADMIN(Set.of(Permission.VIEW_RUNS, Permission.VIEW_HEAL_DIFFS, Permission.START_RUN,
            Permission.MANAGE_TENANT));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean grants(Permission permission) {
        return permissions.contains(permission);
    }
}
