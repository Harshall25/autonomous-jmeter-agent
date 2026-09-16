package com.ai.jmeter.agent.adapter.api;

import com.ai.jmeter.agent.domain.governance.Principal;
import jakarta.servlet.http.HttpServletRequest;

/** Establishes who is calling the control plane. */
public interface PrincipalResolver {

    /**
     * @param request the inbound call
     * @return the principal it is made on behalf of
     * @throws com.ai.jmeter.agent.domain.governance.AccessDeniedException if the caller cannot be
     *                                                                    identified
     */
    Principal resolve(HttpServletRequest request);
}
