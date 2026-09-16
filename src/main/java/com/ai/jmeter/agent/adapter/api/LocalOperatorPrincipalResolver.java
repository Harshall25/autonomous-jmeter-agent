package com.ai.jmeter.agent.adapter.api;

import com.ai.jmeter.agent.domain.governance.Principal;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves every caller to the single local operator, for a control plane nobody else can reach.
 *
 * <p>Bound when tenancy is switched off: one person, one tenant, on their own machine, where
 * demanding an identity header would be ceremony with no security value. The identity it returns
 * is named {@code local-operator} precisely so that an audit trail makes obvious that no identity
 * provider vouched for it.
 */
public final class LocalOperatorPrincipalResolver implements PrincipalResolver {

    @Override
    public Principal resolve(HttpServletRequest request) {
        return Principal.localOperator();
    }
}
