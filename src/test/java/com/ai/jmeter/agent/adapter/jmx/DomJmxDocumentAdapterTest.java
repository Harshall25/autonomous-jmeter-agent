package com.ai.jmeter.agent.adapter.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.jmx.JmxMutation;
import com.ai.jmeter.agent.domain.jmx.JmxStructure;
import com.ai.jmeter.agent.domain.jmx.JmxValidationResult;
import com.ai.jmeter.agent.port.JmxDocumentException;
import com.ai.jmeter.agent.support.TestFixtures;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DomJmxDocumentAdapter")
class DomJmxDocumentAdapterTest {

    private final DomJmxDocumentAdapter adapter = new DomJmxDocumentAdapter();

    private String apply(JmxMutation... mutations) {
        return adapter.apply(TestFixtures.VALID_JMX, List.of(mutations));
    }

    @Nested
    @DisplayName("extractors")
    class Extractors {

        @Test
        @DisplayName("adds a JSON extractor into the hashTree that follows its sampler")
        void addsJsonPathExtractor() {
            String patched = apply(new JmxMutation.AddJsonPathExtractor(
                    "login", "auth_token", "$.access_token", "NOT_FOUND"));

            assertThat(patched)
                    .contains("JSONPostProcessor")
                    .contains("<stringProp name=\"JSONPostProcessor.referenceNames\">auth_token")
                    .contains("<stringProp name=\"JSONPostProcessor.jsonPathExprs\">$.access_token")
                    .contains("NOT_FOUND");
        }

        @Test
        @DisplayName("scopes the extractor to the sampler it was asked for, not the next one")
        void scopesExtractorToItsSampler() {
            // JMeter scopes a post-processor by position. Attaching to the wrong hashTree
            // silently mines the wrong response, which the plan would never report as an error.
            String patched = apply(new JmxMutation.AddJsonPathExtractor(
                    "login", "auth_token", "$.token", "NOT_FOUND"));

            int loginIndex = patched.indexOf("testname=\"login\"");
            int extractorIndex = patched.indexOf("JSONPostProcessor");
            int ordersIndex = patched.indexOf("testname=\"orders\"");

            assertThat(extractorIndex).isBetween(loginIndex, ordersIndex);
        }

        @Test
        @DisplayName("adds a regex extractor that reads response headers")
        void addsHeaderRegexExtractor() {
            String patched = apply(new JmxMutation.AddRegexExtractor(
                    "login", "session", "Set-Cookie: SESSION=(.+?);", "$1$", "NONE", true));

            assertThat(patched)
                    .contains("RegexExtractor")
                    .contains("<stringProp name=\"RegexExtractor.useHeaders\">true")
                    .contains("<stringProp name=\"RegexExtractor.refname\">session")
                    .contains("<stringProp name=\"RegexExtractor.template\">$1$");
        }

        @Test
        @DisplayName("adds a regex extractor that reads the body")
        void addsBodyRegexExtractor() {
            String patched = apply(new JmxMutation.AddRegexExtractor(
                    "orders", "orderId", "\"id\":\"(.+?)\"", "$1$", "NONE", false));

            assertThat(patched).contains("<stringProp name=\"RegexExtractor.useHeaders\">false");
        }

        @Test
        @DisplayName("refuses to attach to a sampler that does not exist")
        void rejectsUnknownSampler() {
            JmxMutation missing = new JmxMutation.AddJsonPathExtractor(
                    "checkout", "token", "$.token", "NONE");

            assertThatThrownBy(() -> apply(missing))
                    .isInstanceOf(JmxDocumentException.class)
                    .hasMessageContaining("No element named 'checkout'");
        }
    }

    @Nested
    @DisplayName("headers")
    class Headers {

        @Test
        @DisplayName("creates a Header Manager on a sampler that has none")
        void createsSamplerHeaderManager() {
            String patched = apply(new JmxMutation.SetHeader(
                    "orders", "Authorization", "Bearer ${auth_token}"));

            assertThat(patched)
                    .contains("HeaderManager")
                    .contains("<stringProp name=\"Header.name\">Authorization")
                    .contains("<stringProp name=\"Header.value\">Bearer ${auth_token}");
        }

        @Test
        @DisplayName("reuses the Header Manager when setting a second header")
        void reusesExistingHeaderManager() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of(
                    new JmxMutation.SetHeader("orders", "Authorization", "Bearer x"),
                    new JmxMutation.SetHeader("orders", "Accept", "application/json")));

            assertThat(patched.split("<HeaderManager ", -1))
                    .as("a second Header Manager on the same sampler would be ignored by JMeter")
                    .hasSize(2);
            assertThat(patched).contains("Authorization").contains("Accept");
        }

        @Test
        @DisplayName("replaces a header rather than duplicating it")
        void replacesExistingHeader() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of(
                    new JmxMutation.SetHeader("orders", "Accept", "text/plain"),
                    new JmxMutation.SetHeader("orders", "Accept", "application/json")));

            assertThat(patched).contains("application/json").doesNotContain("text/plain");
        }

        @Test
        @DisplayName("applies a plan-wide header under the thread group")
        void appliesPlanWideHeader() {
            String patched = apply(new JmxMutation.SetHeader("", "X-Trace", "abc"));

            assertThat(patched).contains("X-Trace");
            int threadGroupIndex = patched.indexOf("testname=\"Shoppers\"");
            assertThat(patched.indexOf("X-Trace")).isGreaterThan(threadGroupIndex);
        }

        @Test
        @DisplayName("treats a null sampler name as plan-wide")
        void nullSamplerNameIsPlanWide() {
            String patched = apply(new JmxMutation.SetHeader(null, "X-Trace", "abc"));

            assertThat(patched).contains("X-Trace");
        }

        @Test
        @DisplayName("reuses a plan-wide Header Manager across calls")
        void reusesPlanWideHeaderManager() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of(
                    new JmxMutation.SetHeader("", "X-Trace", "abc"),
                    new JmxMutation.SetHeader("", "X-Tenant", "acme")));

            assertThat(patched.split("<HeaderManager ", -1)).hasSize(2);
        }

        @Test
        @DisplayName("refuses to set a header on a sampler that does not exist")
        void rejectsUnknownSampler() {
            JmxMutation missing = new JmxMutation.SetHeader("checkout", "Accept", "*/*");

            assertThatThrownBy(() -> apply(missing))
                    .isInstanceOf(JmxDocumentException.class)
                    .hasMessageContaining("No sampler named 'checkout'");
        }
    }

    @Nested
    @DisplayName("test data and workload")
    class TestDataAndWorkload {

        @Test
        @DisplayName("adds a CSV Data Set Config ahead of the samplers that read it")
        void addsCsvDataSet() {
            String patched = apply(new JmxMutation.AddCsvDataSet(
                    "test_data.csv", List.of("username", "password")));

            assertThat(patched)
                    .contains("CSVDataSet")
                    .contains("<stringProp name=\"variableNames\">username,password")
                    .contains("<boolProp name=\"ignoreFirstLine\">true");
            assertThat(patched.indexOf("CSVDataSet"))
                    .as("a feed initialized after its readers supplies them nothing")
                    .isLessThan(patched.indexOf("testname=\"login\""));
        }

        @Test
        @DisplayName("replaces an existing CSV config rather than stacking a second one")
        void replacesExistingCsvDataSet() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of(
                    new JmxMutation.AddCsvDataSet("old.csv", List.of("a")),
                    new JmxMutation.AddCsvDataSet("test_data.csv", List.of("username"))));

            assertThat(patched).contains("test_data.csv").doesNotContain("old.csv");
        }

        @Test
        @DisplayName("sets threads, ramp-up and loops on the thread group")
        void configuresThreadGroup() {
            String patched = apply(new JmxMutation.ConfigureThreadGroup(50, 30, 10));

            assertThat(patched)
                    .contains("<stringProp name=\"ThreadGroup.num_threads\">50")
                    .contains("<stringProp name=\"ThreadGroup.ramp_time\">30")
                    .contains("<stringProp name=\"LoopController.loops\">10");
        }
    }

    @Nested
    @DisplayName("parameterization and removal")
    class ParameterizationAndRemoval {

        @Test
        @DisplayName("replaces a hardcoded literal everywhere it appears")
        void parameterizesLiteral() {
            String patched = apply(new JmxMutation.ReplaceLiteralWithVariable(
                    "api.shop.test", "host"));

            assertThat(patched).contains("${host}").doesNotContain("api.shop.test");
        }

        @Test
        @DisplayName("leaves the plan alone when the literal is not present")
        void ignoresAbsentLiteral() {
            String patched = apply(new JmxMutation.ReplaceLiteralWithVariable("nowhere", "x"));

            assertThat(patched).doesNotContain("${x}").contains("api.shop.test");
        }

        @Test
        @DisplayName("removes a sampler together with its trailing hashTree")
        void removesSampler() {
            String patched = apply(new JmxMutation.RemoveElement("orders"));

            assertThat(patched).doesNotContain("testname=\"orders\"").contains("testname=\"login\"");
            assertThat(adapter.describe(patched).samplerNames()).containsExactly("login");
        }

        @Test
        @DisplayName("is a no-op when the element to remove is already gone")
        void removalOfMissingElementIsNoOp() {
            String patched = apply(new JmxMutation.RemoveElement("checkout"));

            assertThat(adapter.describe(patched).samplerNames()).containsExactly("login", "orders");
        }
    }

    @Nested
    @DisplayName("describe")
    class Describe {

        @Test
        @DisplayName("lists the samplers in execution order")
        void listsSamplers() {
            assertThat(adapter.describe(TestFixtures.VALID_JMX).samplerNames())
                    .containsExactly("login", "orders");
        }

        @Test
        @DisplayName("reports a variable defined by an extractor")
        void reportsExtractorDefinedVariables() {
            String patched = apply(new JmxMutation.AddJsonPathExtractor(
                    "login", "auth_token", "$.token", "NONE"));

            assertThat(adapter.describe(patched).definedVariables()).contains("auth_token");
        }

        @Test
        @DisplayName("reports variables defined by a CSV feed")
        void reportsCsvDefinedVariables() {
            String patched = apply(new JmxMutation.AddCsvDataSet(
                    "test_data.csv", List.of("username", "password")));

            assertThat(adapter.describe(patched).definedVariables())
                    .contains("username", "password");
        }

        @Test
        @DisplayName("spots a reference that nothing defines")
        void spotsUnresolvedReference() {
            String patched = apply(new JmxMutation.SetHeader(
                    "orders", "Authorization", "Bearer ${auth_token}"));

            JmxStructure structure = adapter.describe(patched);

            assertThat(structure.referencedVariables()).contains("auth_token");
            assertThat(structure.unresolvedVariables()).contains("auth_token");
        }

        @Test
        @DisplayName("considers a reference resolved once an extractor defines it")
        void resolvedReferenceIsNotFlagged() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of(
                    new JmxMutation.AddJsonPathExtractor(
                            "login", "auth_token", "$.token", "NONE"),
                    new JmxMutation.SetHeader("orders", "Authorization", "Bearer ${auth_token}")));

            assertThat(adapter.describe(patched).unresolvedVariables()).isEmpty();
        }

        @Test
        @DisplayName("ignores JMeter's built-in functions, which need no definition")
        void ignoresBuiltInFunctions() {
            String patched = apply(new JmxMutation.SetHeader(
                    "orders", "X-Secret", "${__P(agent.secret.credential.1)}"));

            assertThat(adapter.describe(patched).unresolvedVariables()).isEmpty();
        }

        @Test
        @DisplayName("renders a briefing line for the model")
        void rendersBriefing() {
            assertThat(adapter.describe(TestFixtures.VALID_JMX).describe())
                    .contains("Samplers: [login, orders]")
                    .contains("Defined variables:")
                    .contains("Unresolved references:");
        }

        @Test
        @DisplayName("refuses to describe a plan it cannot parse")
        void rejectsMalformedPlan() {
            assertThatThrownBy(() -> adapter.describe("<jmeterTestPlan><unclosed>"))
                    .isInstanceOf(JmxDocumentException.class)
                    .hasMessageContaining("Unable to parse JMeter plan");
        }
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("passes a well-formed, fully wired plan")
        void passesValidPlan() {
            JmxValidationResult result = adapter.validate(TestFixtures.VALID_JMX);

            assertThat(result.isValid()).isTrue();
            assertThat(result.hasWarnings()).isFalse();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("reports malformed XML as a finding rather than throwing")
        void reportsMalformedXml() {
            // This is the condition the agent must detect cheaply and repair, so it has to come
            // back as evidence, not as an exception that aborts the run.
            JmxValidationResult result = adapter.validate("<jmeterTestPlan><unclosed>");

            assertThat(result.isValid()).isFalse();
            assertThat(result.describe()).contains("ERROR").contains("not well-formed XML");
        }

        @Test
        @DisplayName("rejects a document that is not a JMeter plan")
        void rejectsWrongRootElement() {
            JmxValidationResult result = adapter.validate("<project><TestPlan/></project>");

            assertThat(result.errors())
                    .anyMatch(error -> error.contains("Root element must be"));
        }

        @Test
        @DisplayName("rejects a plan with no TestPlan element")
        void rejectsMissingTestPlan() {
            JmxValidationResult result = adapter.validate(
                    "<jmeterTestPlan><hashTree/></jmeterTestPlan>");

            assertThat(result.errors()).anyMatch(error -> error.contains("no <TestPlan>"));
        }

        @Test
        @DisplayName("rejects a plan with nothing that would execute")
        void rejectsMissingThreadGroup() {
            JmxValidationResult result = adapter.validate(
                    "<jmeterTestPlan><hashTree><TestPlan/><hashTree/></hashTree></jmeterTestPlan>");

            assertThat(result.errors())
                    .anyMatch(error -> error.contains("no thread group"))
                    .anyMatch(error -> error.contains("no samplers"));
        }

        @Test
        @DisplayName("warns about a reference nothing defines, without blocking the run")
        void warnsAboutUnresolvedVariables() {
            String patched = apply(new JmxMutation.SetHeader(
                    "orders", "Authorization", "Bearer ${auth_token}"));

            JmxValidationResult result = adapter.validate(patched);

            assertThat(result.isValid())
                    .as("an unresolved reference is a bad plan, not an unrunnable one")
                    .isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.describe())
                    .contains("WARNING")
                    .contains("${auth_token}")
                    .contains("usual cause of a 401");
        }
    }

    @Nested
    @DisplayName("irregular plan shapes")
    class IrregularPlans {

        /** A sampler with no trailing hashTree, which JMeter itself tolerates. */
        private static final String SAMPLER_WITHOUT_HASHTREE = """
                <jmeterTestPlan>
                  <hashTree>
                    <TestPlan testname="Plan"/>
                    <hashTree>
                      <ThreadGroup testname="Group"/>
                      <hashTree>
                        <HTTPSamplerProxy testname="login"/>
                      </hashTree>
                    </hashTree>
                  </hashTree>
                </jmeterTestPlan>""";

        @Test
        @DisplayName("creates the missing hashTree when attaching to a bare sampler")
        void attachesToSamplerWithoutHashTree() {
            String patched = adapter.apply(SAMPLER_WITHOUT_HASHTREE, List.of(
                    new JmxMutation.AddJsonPathExtractor("login", "token", "$.token", "NONE")));

            assertThat(patched).contains("JSONPostProcessor").contains("token");
        }

        @Test
        @DisplayName("creates the missing hashTree when setting a header on a bare sampler")
        void setsHeaderOnSamplerWithoutHashTree() {
            String patched = adapter.apply(SAMPLER_WITHOUT_HASHTREE, List.of(
                    new JmxMutation.SetHeader("login", "Accept", "application/json")));

            assertThat(patched).contains("HeaderManager").contains("Accept");
        }

        @Test
        @DisplayName("adds thread group properties that the plan never declared")
        void addsMissingThreadGroupProperties() {
            String patched = adapter.apply(SAMPLER_WITHOUT_HASHTREE, List.of(
                    new JmxMutation.ConfigureThreadGroup(20, 5, 3)));

            assertThat(patched)
                    .contains("<stringProp name=\"ThreadGroup.num_threads\">20")
                    .contains("<stringProp name=\"ThreadGroup.ramp_time\">5");
        }

        @Test
        @DisplayName("refuses to add a CSV feed to a plan with nothing to feed")
        void rejectsCsvWithoutThreadGroup() {
            List<JmxMutation> edits =
                    List.of(new JmxMutation.AddCsvDataSet("test_data.csv", List.of("user")));

            assertThatThrownBy(() -> adapter.apply(
                    "<jmeterTestPlan><hashTree><TestPlan/></hashTree></jmeterTestPlan>", edits))
                    .isInstanceOf(JmxDocumentException.class)
                    .hasMessageContaining("no thread group");
        }

        @Test
        @DisplayName("refuses a plan-wide header when there is no thread group to hold it")
        void rejectsPlanWideHeaderWithoutThreadGroup() {
            List<JmxMutation> edits = List.of(new JmxMutation.SetHeader("", "Accept", "*/*"));

            assertThatThrownBy(() -> adapter.apply(
                    "<jmeterTestPlan><hashTree><TestPlan/></hashTree></jmeterTestPlan>", edits))
                    .isInstanceOf(JmxDocumentException.class)
                    .hasMessageContaining("no thread group");
        }

        @Test
        @DisplayName("refuses a CSV feed when the thread group has no hashTree to hold it")
        void rejectsCsvWhenThreadGroupHasNoHashTree() {
            String noHashTree = """
                    <jmeterTestPlan><hashTree>
                      <TestPlan testname="Plan"/>
                      <hashTree><ThreadGroup testname="Group"/></hashTree>
                    </hashTree></jmeterTestPlan>""";
            List<JmxMutation> edits =
                    List.of(new JmxMutation.AddCsvDataSet("test_data.csv", List.of("user")));

            assertThatThrownBy(() -> adapter.apply(noHashTree, edits))
                    .isInstanceOf(JmxDocumentException.class)
                    .hasMessageContaining("no hashTree");
        }

        @Test
        @DisplayName("reads variables declared as user-defined arguments")
        void readsUserDefinedVariables() {
            String withArguments = """
                    <jmeterTestPlan><hashTree>
                      <TestPlan testname="Plan"/>
                      <hashTree>
                        <ThreadGroup testname="Group"/>
                        <hashTree>
                          <Arguments testname="User Defined Variables">
                            <collectionProp name="Arguments.arguments">
                              <elementProp name="host" elementType="Argument">
                                <stringProp name="Argument.value">api.shop.test</stringProp>
                              </elementProp>
                              <elementProp name="" elementType="Argument">
                                <stringProp name="Argument.value">unnamed</stringProp>
                              </elementProp>
                            </collectionProp>
                          </Arguments>
                          <hashTree/>
                          <HTTPSamplerProxy testname="login">
                            <stringProp name="HTTPSampler.domain">${host}</stringProp>
                          </HTTPSamplerProxy>
                          <hashTree/>
                        </hashTree>
                      </hashTree>
                    </hashTree></jmeterTestPlan>""";

            JmxStructure structure = adapter.describe(withArguments);

            assertThat(structure.definedVariables())
                    .as("an unnamed argument defines nothing and must not be listed")
                    .containsExactly("host");
            assertThat(structure.unresolvedVariables()).isEmpty();
        }

        @Test
        @DisplayName("inserts a hashTree when the next sibling is another sampler")
        void insertsHashTreeBetweenAdjacentSamplers() {
            String adjacent = """
                    <jmeterTestPlan><hashTree>
                      <TestPlan testname="Plan"/>
                      <hashTree>
                        <ThreadGroup testname="Group"/>
                        <hashTree>
                          <HTTPSamplerProxy testname="login"/>
                          <HTTPSamplerProxy testname="orders"/>
                        </hashTree>
                      </hashTree>
                    </hashTree></jmeterTestPlan>""";

            String patched = adapter.apply(adjacent, List.of(
                    new JmxMutation.AddJsonPathExtractor("login", "token", "$.token", "NONE")));

            assertThat(patched).contains("JSONPostProcessor");
            assertThat(patched.indexOf("JSONPostProcessor"))
                    .as("the extractor belongs to login, so it must precede orders")
                    .isLessThan(patched.indexOf("testname=\"orders\""));
        }

        @Test
        @DisplayName("populates a Header Manager that was declared without a header collection")
        void populatesEmptyHeaderManager() {
            String bareManager = """
                    <jmeterTestPlan><hashTree>
                      <TestPlan testname="Plan"/>
                      <hashTree>
                        <ThreadGroup testname="Group"/>
                        <hashTree>
                          <HTTPSamplerProxy testname="login"/>
                          <hashTree>
                            <HeaderManager testname="HTTP Header Manager"/>
                            <hashTree/>
                          </hashTree>
                        </hashTree>
                      </hashTree>
                    </hashTree></jmeterTestPlan>""";

            String patched = adapter.apply(bareManager, List.of(
                    new JmxMutation.SetHeader("login", "Accept", "application/json")));

            assertThat(patched)
                    .contains("HeaderManager.headers")
                    .contains("<stringProp name=\"Header.name\">Accept");
        }

        @Test
        @DisplayName("refuses a plan-wide header when the thread group has no hashTree")
        void rejectsPlanWideHeaderWhenThreadGroupHasNoHashTree() {
            String noHashTree = """
                    <jmeterTestPlan><hashTree>
                      <TestPlan testname="Plan"/>
                      <hashTree><ThreadGroup testname="Group"/></hashTree>
                    </hashTree></jmeterTestPlan>""";
            List<JmxMutation> edits = List.of(new JmxMutation.SetHeader("", "Accept", "*/*"));

            assertThatThrownBy(() -> adapter.apply(noHashTree, edits))
                    .isInstanceOf(JmxDocumentException.class)
                    .hasMessageContaining("no hashTree to hold a Header Manager");
        }

        @Test
        @DisplayName("reads a variable defined by a regex extractor")
        void readsRegexExtractorVariable() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of(
                    new JmxMutation.AddRegexExtractor(
                            "login", "session", "S=(.+?);", "$1$", "NONE", true)));

            assertThat(adapter.describe(patched).definedVariables()).contains("session");
        }

        @Test
        @DisplayName("ignores a blank name in a comma-separated variable list")
        void ignoresBlankVariableNames() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of(
                    new JmxMutation.AddCsvDataSet("f.csv", List.of("user", "", "pass"))));

            assertThat(adapter.describe(patched).definedVariables())
                    .containsExactly("user", "pass");
        }
    }

    @Nested
    @DisplayName("hardening")
    class Hardening {

        @Test
        @DisplayName("refuses a plan carrying a document type declaration")
        void rejectsDoctype() {
            // The XML was written by a model reasoning over attacker-influenceable traffic, so
            // an external entity reference is a live file-disclosure vector.
            String xxe = """
                    <?xml version="1.0"?>
                    <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <jmeterTestPlan>&xxe;</jmeterTestPlan>""";

            JmxValidationResult result = adapter.validate(xxe);

            assertThat(result.isValid()).isFalse();
            assertThat(result.describe()).contains("not well-formed XML");
        }
    }

    @Nested
    @DisplayName("applying several edits")
    class Composition {

        @Test
        @DisplayName("applies each edit to the result of the last")
        void appliesInOrder() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of(
                    new JmxMutation.AddJsonPathExtractor(
                            "login", "auth_token", "$.access_token", "NONE"),
                    new JmxMutation.SetHeader("orders", "Authorization", "Bearer ${auth_token}"),
                    new JmxMutation.AddCsvDataSet("test_data.csv", List.of("username")),
                    new JmxMutation.ConfigureThreadGroup(25, 15, 5)));

            assertThat(adapter.validate(patched).isValid()).isTrue();
            assertThat(adapter.describe(patched).unresolvedVariables()).isEmpty();
            assertThat(patched)
                    .contains("JSONPostProcessor")
                    .contains("HeaderManager")
                    .contains("CSVDataSet")
                    .contains("<stringProp name=\"ThreadGroup.num_threads\">25");
        }

        @Test
        @DisplayName("returns the plan unchanged when given no edits")
        void emptyMutationListIsHarmless() {
            String patched = adapter.apply(TestFixtures.VALID_JMX, List.of());

            assertThat(adapter.describe(patched).samplerNames()).containsExactly("login", "orders");
        }

        @Test
        @DisplayName("refuses to edit a plan it cannot parse")
        void rejectsMalformedPlan() {
            List<JmxMutation> edits = List.of(new JmxMutation.ConfigureThreadGroup(1, 1, 1));

            assertThatThrownBy(() -> adapter.apply("<not-xml", edits))
                    .isInstanceOf(JmxDocumentException.class);
        }

        @Test
        @DisplayName("reports a document that cannot be written back out")
        void reportsSerializationFailure() throws TransformerConfigurationException {
            TransformerFactory failing = mock(TransformerFactory.class);
            when(failing.newTransformer())
                    .thenThrow(new TransformerConfigurationException("no transformer available"));

            DomJmxDocumentAdapter brittle = new DomJmxDocumentAdapter(failing);

            assertThatThrownBy(() -> brittle.apply(TestFixtures.VALID_JMX, List.of()))
                    .isInstanceOf(JmxDocumentException.class)
                    .hasMessageContaining("Unable to serialize JMeter plan");
        }
    }
}
