package com.ai.jmeter.agent.support;

import com.ai.jmeter.agent.adapter.ai.PromptCatalog;
import com.ai.jmeter.agent.config.AgentProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.core.io.ClassPathResource;

/** Shared fixtures so a signature change lands in one place rather than across every test. */
public final class TestFixtures {

    /**
     * A structurally valid JMeter 5.6 plan with two samplers.
     *
     * <p>Faithful to the format's quirk that an element's children live in the {@code hashTree}
     * that <em>follows</em> it, which is the behaviour the mutation adapter has to get right.
     */
    public static final String VALID_JMX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
              <hashTree>
                <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Generated Plan" enabled="true">
                  <boolProp name="TestPlan.functional_mode">false</boolProp>
                </TestPlan>
                <hashTree>
                  <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Shoppers" enabled="true">
                    <stringProp name="ThreadGroup.num_threads">1</stringProp>
                    <stringProp name="ThreadGroup.ramp_time">1</stringProp>
                    <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
                      <stringProp name="LoopController.loops">1</stringProp>
                    </elementProp>
                  </ThreadGroup>
                  <hashTree>
                    <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="login" enabled="true">
                      <stringProp name="HTTPSampler.domain">api.shop.test</stringProp>
                      <stringProp name="HTTPSampler.path">/v1/login</stringProp>
                      <stringProp name="HTTPSampler.method">POST</stringProp>
                    </HTTPSamplerProxy>
                    <hashTree/>
                    <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="orders" enabled="true">
                      <stringProp name="HTTPSampler.domain">api.shop.test</stringProp>
                      <stringProp name="HTTPSampler.path">/v1/orders</stringProp>
                      <stringProp name="HTTPSampler.method">GET</stringProp>
                    </HTTPSamplerProxy>
                    <hashTree/>
                  </hashTree>
                </hashTree>
              </hashTree>
            </jmeterTestPlan>
            """;

    private TestFixtures() {
    }

    public static PromptCatalog promptCatalog() {
        return new PromptCatalog(
                new ClassPathResource("prompts/api-jmeter-system.st"),
                new ClassPathResource("prompts/sql-jmeter-system.st"),
                new ClassPathResource("prompts/heal-script.st"),
                new ClassPathResource("prompts/repair-plan.st"));
    }

    public static AgentProperties properties() {
        return properties(Path.of("/opt/jmeter/bin"), null);
    }

    public static AgentProperties properties(Path homePath, Path libPath) {
        return new AgentProperties(
                homePath, 3, Path.of("workspace"), Duration.ofMinutes(10), libPath,
                150, 2000, 200, 8000, 500, false);
    }
}
