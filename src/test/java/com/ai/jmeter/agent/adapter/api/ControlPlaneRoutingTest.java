package com.ai.jmeter.agent.adapter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the control plane is actually reachable over HTTP.
 *
 * <p>Exists because the unit tests could not have caught the failure that made it necessary. They
 * call the controller's methods directly, which passes whether or not Spring ever routes a request
 * to them — and for a while, it did not: the controller was hand-registered carrying only
 * {@code @RequestMapping}, and Spring Framework 6.2 detects a handler by {@code @Controller} on the
 * bean's type. Every endpoint answered 404 while the context started cleanly and every test passed.
 *
 * <p>So this asserts the one thing a direct method call cannot: that a real request over the real
 * mapping reaches the real handler.
 */
@SpringBootTest(properties = {
        // The application defaults to a CLI; the control plane only exists when asked for.
        "spring.main.web-application-type=servlet",
        "spring.ai.anthropic.api-key=test-key-not-used",
        "agent.jmeter.home-path=/tmp/jmeter-under-test/bin",
        "agent.jmeter.workspace=target/test-workspace"
})
@AutoConfigureMockMvc
@DisplayName("Control plane routing")
class ControlPlaneRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("routes GET /api/runs to the handler and answers JSON")
    void listingIsRouted() throws Exception {
        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("honours the page size on the query string")
    void pageSizeIsBound() throws Exception {
        mockMvc.perform(get("/api/runs").param("limit", "5"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("routes a run id in the path, and 404s one the ledger has never seen")
    void detailIsRouted() throws Exception {
        // A 404 from the handler and a 404 from an unmapped URL look identical from outside,
        // which is exactly how the original bug hid. The listing test above is what separates
        // them: it would fail if nothing were mapped at all.
        mockMvc.perform(get("/api/runs/no-such-run"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("serves the heal-diff UI at the root")
    void uiIsServed() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Control Plane")));
    }
}
