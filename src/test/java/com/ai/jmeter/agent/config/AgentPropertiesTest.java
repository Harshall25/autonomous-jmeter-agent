package com.ai.jmeter.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import com.ai.jmeter.agent.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("AgentProperties")
class AgentPropertiesTest {

    private static AgentProperties properties(Path homePath, Path libPath) {
        return TestFixtures.properties(homePath, libPath);
    }

    @Test
    @DisplayName("derives the lib directory as a sibling of JMeter's bin directory")
    void derivesLibFromHome() {
        assertThat(properties(Path.of("/opt/jmeter/bin"), null).resolvedLibPath())
                .isEqualTo(Path.of("/opt/jmeter/lib"));
    }

    @Test
    @DisplayName("prefers an explicitly configured lib directory")
    void prefersExplicitLibPath() {
        assertThat(properties(Path.of("/opt/jmeter/bin"), Path.of("/custom/drivers"))
                .resolvedLibPath())
                .isEqualTo(Path.of("/custom/drivers"));
    }

    @Test
    @DisplayName("falls back to a nested lib directory when the home path has no parent")
    void handlesParentlessHomePath() {
        assertThat(properties(Path.of("bin"), null).resolvedLibPath())
                .isEqualTo(Path.of("bin/lib"));
    }

    @Test
    @DisplayName("binds the documented property names and supplies defaults for the rest")
    void bindsFromConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(EnableAgentProperties.class)
                .withPropertyValues(
                        "agent.jmeter.home-path=/srv/jmeter/bin",
                        "agent.jmeter.max-retries=5",
                        "agent.jmeter.execution-timeout=90s")
                .run(context -> {
                    AgentProperties bound = context.getBean(AgentProperties.class);

                    assertThat(bound.homePath()).isEqualTo(Path.of("/srv/jmeter/bin"));
                    assertThat(bound.maxRetries()).isEqualTo(5);
                    assertThat(bound.executionTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(bound.workspace()).isEqualTo(Path.of("workspace"));
                    assertThat(bound.maxHarEntries()).isEqualTo(150);
                    assertThat(bound.maxHarBodyCharacters()).isEqualTo(2000);
                    assertThat(bound.maxSqlQueries()).isEqualTo(200);
                    assertThat(bound.maxProcessOutputCharacters()).isEqualTo(8000);
                    assertThat(bound.maxRecordedFailures()).isEqualTo(500);
                    assertThat(bound.resolvedLibPath()).isEqualTo(Path.of("/srv/jmeter/lib"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class EnableAgentProperties {
    }

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("treats a missing or blank signing key as no key at all")
        void recognizesAnAbsentSigningKey() {
            assertThat(tenancy(null).hasSigningKey()).isFalse();
            assertThat(tenancy("   ").hasSigningKey()).isFalse();
            assertThat(tenancy("a-thirty-two-character-test-key!!").hasSigningKey()).isTrue();
        }

        private TenancyProperties tenancy(String signingKey) {
            return new TenancyProperties(true, "acme", signingKey, "prompts@v3",
                    "X-Auth-Subject", "X-Auth-Tenant", "X-Auth-Roles");
        }
    }
}
