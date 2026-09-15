package com.ai.jmeter.agent.adapter.jmx;

import com.ai.jmeter.agent.domain.jmx.JmxMutation;
import com.ai.jmeter.agent.domain.jmx.JmxStructure;
import com.ai.jmeter.agent.domain.jmx.JmxValidationResult;
import com.ai.jmeter.agent.port.JmxDocumentException;
import com.ai.jmeter.agent.port.JmxDocumentPort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Driven adapter: manipulates JMeter plans as a DOM tree.
 *
 * <p>Encodes the part of JMeter's file format that matters for correlation: an element's children
 * do not nest inside it, they live in the {@code hashTree} that immediately follows it as a
 * sibling. Getting that wrong produces a file JMeter parses without complaint and then ignores,
 * which is exactly the kind of silent failure a model generating raw XML falls into.
 *
 * <p>The parser is hardened against XXE. The XML being parsed was written by a language model from
 * attacker-influenceable traffic, so it is untrusted input by construction.
 */
public final class DomJmxDocumentAdapter implements JmxDocumentPort {

    private static final Logger log = LoggerFactory.getLogger(DomJmxDocumentAdapter.class);

    private static final String ROOT_ELEMENT = "jmeterTestPlan";
    private static final String HASH_TREE = "hashTree";
    private static final String TESTNAME = "testname";

    /** Elements that issue a request, and therefore can carry a post-processor. */
    private static final Set<String> SAMPLER_ELEMENTS = Set.of(
            "HTTPSamplerProxy", "JDBCSampler", "JSR223Sampler", "DebugSampler",
            "GraphQLHTTPSampler", "GrpcSampler", "WebSocketSampler", "KafkaProducerSampler");

    /** Elements that make a variable available to the rest of the plan. */
    private static final Set<String> VARIABLE_DEFINING_ELEMENTS = Set.of(
            "CSVDataSet", "JSONPostProcessor", "RegexExtractor", "Arguments",
            "BoundaryExtractor", "XPath2Extractor", "JSR223PostProcessor");

    /** Matches {@code ${var}} but not {@code ${__function(...)}}, which needs no definition. */
    private static final Pattern VARIABLE_REFERENCE =
            Pattern.compile("\\$\\{(?!__)([A-Za-z0-9_]+)}");

    private final TransformerFactory transformerFactory;

    public DomJmxDocumentAdapter() {
        this(hardenedTransformerFactory());
    }

    /** Seam for verifying the serialization failure path, which no valid document reaches. */
    DomJmxDocumentAdapter(TransformerFactory transformerFactory) {
        this.transformerFactory = transformerFactory;
    }

    private static TransformerFactory hardenedTransformerFactory() {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return factory;
    }

    @Override
    public String apply(String jmx, List<JmxMutation> mutations) {
        Document document = parse(jmx);
        for (JmxMutation mutation : mutations) {
            applyOne(document, mutation);
            log.debug("Applied mutation: {}", mutation.describe());
        }
        return serialize(document);
    }

    @Override
    public JmxValidationResult validate(String jmx) {
        Document document;
        try {
            document = parse(jmx);
        } catch (JmxDocumentException e) {
            // Malformed XML is a validation finding, not an exception: it is precisely the
            // condition the agent is expected to detect cheaply and repair.
            return JmxValidationResult.error("Plan is not well-formed XML: " + e.getMessage());
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!ROOT_ELEMENT.equals(document.getDocumentElement().getNodeName())) {
            errors.add("Root element must be <%s>, found <%s>".formatted(
                    ROOT_ELEMENT, document.getDocumentElement().getNodeName()));
        }
        if (elementsByTag(document, "TestPlan").isEmpty()) {
            errors.add("Plan contains no <TestPlan> element");
        }
        if (threadGroups(document).isEmpty()) {
            errors.add("Plan contains no thread group, so nothing would execute");
        }

        JmxStructure structure = structureOf(document);
        if (structure.samplerNames().isEmpty()) {
            errors.add("Plan contains no samplers, so nothing would be measured");
        }
        structure.unresolvedVariables().forEach(variable -> warnings.add(
                "Variable ${%s} is referenced but never defined by a CSV feed, extractor or "
                        .formatted(variable)
                        + "user-defined variable — this is the usual cause of a 401 or empty "
                        + "parameter at run time"));

        return new JmxValidationResult(errors, warnings);
    }

    @Override
    public JmxStructure describe(String jmx) {
        return structureOf(parse(jmx));
    }

    private void applyOne(Document document, JmxMutation mutation) {
        switch (mutation) {
            case JmxMutation.AddJsonPathExtractor extractor -> addJsonPathExtractor(
                    document, extractor);
            case JmxMutation.AddRegexExtractor extractor -> addRegexExtractor(document, extractor);
            case JmxMutation.SetHeader header -> setHeader(document, header);
            case JmxMutation.AddCsvDataSet csv -> addCsvDataSet(document, csv);
            case JmxMutation.ConfigureThreadGroup workload -> configureThreadGroup(
                    document, workload);
            case JmxMutation.ReplaceLiteralWithVariable replacement -> replaceLiteral(
                    document, replacement);
            case JmxMutation.RemoveElement removal -> removeElement(document, removal);
        }
    }

    private void addJsonPathExtractor(Document document, JmxMutation.AddJsonPathExtractor spec) {
        Element extractor = createElement(document, "JSONPostProcessor",
                "JSONPostProcessorGui", "JSONPostProcessor",
                "Extract " + spec.variableName());
        appendStringProp(extractor, "JSONPostProcessor.referenceNames", spec.variableName());
        appendStringProp(extractor, "JSONPostProcessor.jsonPathExprs", spec.jsonPath());
        appendStringProp(extractor, "JSONPostProcessor.match_numbers", "1");
        appendStringProp(extractor, "JSONPostProcessor.defaultValues", spec.defaultValue());
        attachToSampler(document, spec.samplerName(), extractor);
    }

    private void addRegexExtractor(Document document, JmxMutation.AddRegexExtractor spec) {
        Element extractor = createElement(document, "RegexExtractor",
                "RegexExtractorGui", "RegexExtractor", "Extract " + spec.variableName());
        appendStringProp(extractor, "RegexExtractor.useHeaders",
                String.valueOf(spec.useHeaders()));
        appendStringProp(extractor, "RegexExtractor.refname", spec.variableName());
        appendStringProp(extractor, "RegexExtractor.regex", spec.regex());
        appendStringProp(extractor, "RegexExtractor.template", spec.template());
        appendStringProp(extractor, "RegexExtractor.default", spec.defaultValue());
        appendStringProp(extractor, "RegexExtractor.match_number", "1");
        attachToSampler(document, spec.samplerName(), extractor);
    }

    private void setHeader(Document document, JmxMutation.SetHeader spec) {
        Element headerManager = spec.isPlanWide()
                ? findOrCreatePlanWideHeaderManager(document)
                : findOrCreateSamplerHeaderManager(document, spec.samplerName());

        Element collection = childElement(headerManager, "collectionProp")
                .orElseGet(() -> {
                    Element created = document.createElement("collectionProp");
                    created.setAttribute("name", "HeaderManager.headers");
                    headerManager.appendChild(created);
                    return created;
                });

        removeExistingHeader(collection, spec.headerName());

        Element header = document.createElement("elementProp");
        header.setAttribute("name", "");
        header.setAttribute("elementType", "Header");
        appendStringProp(header, "Header.name", spec.headerName());
        appendStringProp(header, "Header.value", spec.headerValue());
        collection.appendChild(header);
    }

    private void removeExistingHeader(Element collection, String headerName) {
        for (Element existing : childElements(collection, "elementProp")) {
            boolean matches = childElements(existing, "stringProp").stream()
                    .anyMatch(prop -> "Header.name".equals(prop.getAttribute("name"))
                            && headerName.equals(prop.getTextContent()));
            if (matches) {
                collection.removeChild(existing);
            }
        }
    }

    private void addCsvDataSet(Document document, JmxMutation.AddCsvDataSet spec) {
        Element threadGroup = firstThreadGroup(document);
        Element container = followingHashTree(threadGroup)
                .orElseThrow(() -> new JmxDocumentException(
                        "Thread group has no hashTree to hold a CSV Data Set Config"));

        for (Element existing : childElements(container, "CSVDataSet")) {
            removeWithTrailingHashTree(existing);
        }

        Element csv = createElement(document, "CSVDataSet",
                "TestBeanGUI", "CSVDataSet", "Test Data");
        appendStringProp(csv, "filename", spec.filename());
        appendStringProp(csv, "variableNames", String.join(",", spec.variableNames()));
        appendStringProp(csv, "delimiter", ",");
        appendBoolProp(csv, "ignoreFirstLine", true);
        appendBoolProp(csv, "recycle", true);
        appendBoolProp(csv, "stopThread", false);
        appendStringProp(csv, "shareMode", "shareMode.all");

        // Prepended, not appended: a CSV feed must be initialized before the samplers that read
        // from it are reached.
        Node first = container.getFirstChild();
        container.insertBefore(csv, first);
        container.insertBefore(document.createElement(HASH_TREE), csv.getNextSibling());
    }

    private void configureThreadGroup(Document document, JmxMutation.ConfigureThreadGroup spec) {
        for (Element threadGroup : threadGroups(document)) {
            setStringProp(threadGroup, "ThreadGroup.num_threads", String.valueOf(spec.threads()));
            setStringProp(threadGroup, "ThreadGroup.ramp_time",
                    String.valueOf(spec.rampUpSeconds()));
            childElement(threadGroup, "elementProp").ifPresent(controller ->
                    setStringProp(controller, "LoopController.loops",
                            String.valueOf(spec.loops())));
        }
    }

    private void replaceLiteral(Document document, JmxMutation.ReplaceLiteralWithVariable spec) {
        String replacement = "${%s}".formatted(spec.variableName());
        for (Element property : elementsByTag(document, "stringProp")) {
            String text = property.getTextContent();
            if (text.contains(spec.literal())) {
                property.setTextContent(text.replace(spec.literal(), replacement));
            }
        }
    }

    private void removeElement(Document document, JmxMutation.RemoveElement spec) {
        findByTestName(document, spec.testName())
                .ifPresent(DomJmxDocumentAdapter::removeWithTrailingHashTree);
    }

    /**
     * Inserts a post-processor into the hashTree that follows its sampler.
     *
     * <p>JMeter scopes a post-processor to the element it is a sibling-child of, so attaching to
     * the wrong hashTree silently extracts from the wrong response.
     */
    private void attachToSampler(Document document, String samplerName, Element processor) {
        Element sampler = findByTestName(document, samplerName)
                .orElseThrow(() -> new JmxDocumentException(
                        "No element named '%s' to attach %s to".formatted(
                                samplerName, processor.getNodeName())));
        Element container = followingHashTree(sampler)
                .orElseGet(() -> insertHashTreeAfter(document, sampler));
        container.appendChild(processor);
        container.appendChild(document.createElement(HASH_TREE));
    }

    private Element findOrCreateSamplerHeaderManager(Document document, String samplerName) {
        Element sampler = findByTestName(document, samplerName)
                .orElseThrow(() -> new JmxDocumentException(
                        "No sampler named '%s' to set a header on".formatted(samplerName)));
        Element container = followingHashTree(sampler)
                .orElseGet(() -> insertHashTreeAfter(document, sampler));
        return childElements(container, "HeaderManager").stream()
                .findFirst()
                .orElseGet(() -> appendHeaderManager(document, container));
    }

    private Element findOrCreatePlanWideHeaderManager(Document document) {
        Element container = followingHashTree(firstThreadGroup(document))
                .orElseThrow(() -> new JmxDocumentException(
                        "Thread group has no hashTree to hold a Header Manager"));
        return childElements(container, "HeaderManager").stream()
                .findFirst()
                .orElseGet(() -> appendHeaderManager(document, container));
    }

    private Element appendHeaderManager(Document document, Element container) {
        Element manager = createElement(document, "HeaderManager",
                "HeaderPanel", "HeaderManager", "HTTP Header Manager");
        Element collection = document.createElement("collectionProp");
        collection.setAttribute("name", "HeaderManager.headers");
        manager.appendChild(collection);
        container.appendChild(manager);
        container.appendChild(document.createElement(HASH_TREE));
        return manager;
    }

    private JmxStructure structureOf(Document document) {
        List<String> samplers = new ArrayList<>();
        for (Element element : allElements(document)) {
            if (SAMPLER_ELEMENTS.contains(element.getNodeName())) {
                samplers.add(element.getAttribute(TESTNAME));
            }
        }

        Set<String> defined = new LinkedHashSet<>();
        for (Element element : allElements(document)) {
            if (VARIABLE_DEFINING_ELEMENTS.contains(element.getNodeName())) {
                defined.addAll(definedBy(element));
            }
        }

        Set<String> referenced = new LinkedHashSet<>();
        Matcher matcher = VARIABLE_REFERENCE.matcher(textOf(document));
        while (matcher.find()) {
            referenced.add(matcher.group(1));
        }

        return new JmxStructure(samplers, List.copyOf(defined), List.copyOf(referenced));
    }

    private List<String> definedBy(Element element) {
        List<String> names = new ArrayList<>();
        for (Element property : childElements(element, "stringProp")) {
            String name = property.getAttribute("name");
            if (name.endsWith("referenceNames") || name.endsWith("refname")
                    || "variableNames".equals(name)) {
                for (String candidate : property.getTextContent().split("[,;]")) {
                    if (!candidate.isBlank()) {
                        names.add(candidate.trim());
                    }
                }
            }
        }
        // User-defined variables declare each name on a nested elementProp instead.
        for (Element nested : childElements(element, "collectionProp")) {
            for (Element entry : childElements(nested, "elementProp")) {
                String name = entry.getAttribute("name");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static String textOf(Document document) {
        StringBuilder text = new StringBuilder();
        for (Element element : allElements(document)) {
            text.append(element.getAttribute(TESTNAME)).append(' ');
            Node child = element.getFirstChild();
            while (child != null) {
                if (child.getNodeType() == Node.TEXT_NODE) {
                    text.append(child.getNodeValue()).append(' ');
                }
                child = child.getNextSibling();
            }
        }
        return text.toString();
    }

    private Element firstThreadGroup(Document document) {
        List<Element> groups = threadGroups(document);
        if (groups.isEmpty()) {
            throw new JmxDocumentException("Plan contains no thread group");
        }
        return groups.get(0);
    }

    private static List<Element> threadGroups(Document document) {
        List<Element> groups = new ArrayList<>();
        for (Element element : allElements(document)) {
            if (element.getNodeName().endsWith("ThreadGroup")) {
                groups.add(element);
            }
        }
        return groups;
    }

    private static Optional<Element> findByTestName(Document document, String testName) {
        for (Element element : allElements(document)) {
            if (testName.equals(element.getAttribute(TESTNAME))) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    /** @return the {@code hashTree} sibling that holds an element's children, if it has one. */
    private static Optional<Element> followingHashTree(Element element) {
        Node sibling = element.getNextSibling();
        while (sibling != null) {
            if (sibling.getNodeType() == Node.ELEMENT_NODE) {
                return HASH_TREE.equals(sibling.getNodeName())
                        ? Optional.of((Element) sibling)
                        : Optional.empty();
            }
            sibling = sibling.getNextSibling();
        }
        return Optional.empty();
    }

    private static Element insertHashTreeAfter(Document document, Element element) {
        Element hashTree = document.createElement(HASH_TREE);
        element.getParentNode().insertBefore(hashTree, element.getNextSibling());
        return hashTree;
    }

    private static void removeWithTrailingHashTree(Element element) {
        followingHashTree(element).ifPresent(
                hashTree -> hashTree.getParentNode().removeChild(hashTree));
        element.getParentNode().removeChild(element);
    }

    private static List<Element> elementsByTag(Document document, String tag) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    private static List<Element> allElements(Document document) {
        return elementsByTag(document, "*");
    }

    private static List<Element> childElements(Element parent, String tag) {
        List<Element> children = new ArrayList<>();
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                children.add((Element) child);
            }
            child = child.getNextSibling();
        }
        return children;
    }

    private static Optional<Element> childElement(Element parent, String tag) {
        return childElements(parent, tag).stream().findFirst();
    }

    private static void appendStringProp(Element parent, String name, String value) {
        Element property = parent.getOwnerDocument().createElement("stringProp");
        property.setAttribute("name", name);
        property.setTextContent(value);
        parent.appendChild(property);
    }

    private static void appendBoolProp(Element parent, String name, boolean value) {
        Element property = parent.getOwnerDocument().createElement("boolProp");
        property.setAttribute("name", name);
        property.setTextContent(String.valueOf(value));
        parent.appendChild(property);
    }

    private static void setStringProp(Element parent, String name, String value) {
        for (Element property : childElements(parent, "stringProp")) {
            if (name.equals(property.getAttribute("name"))) {
                property.setTextContent(value);
                return;
            }
        }
        appendStringProp(parent, name, value);
    }

    private static Element createElement(
            Document document, String tag, String guiClass, String testClass, String testName) {
        Element element = document.createElement(tag);
        element.setAttribute("guiclass", guiClass);
        element.setAttribute("testclass", testClass);
        element.setAttribute(TESTNAME, testName);
        element.setAttribute("enabled", "true");
        return element;
    }

    private Document parse(String jmx) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The XML came from a model reasoning over attacker-influenceable traffic. Treat it
            // as hostile: no DTDs, no external entities, no schema resolution.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(
                    new ByteArrayInputStream(jmx.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();
            return document;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new JmxDocumentException("Unable to parse JMeter plan: " + e.getMessage(), e);
        }
    }

    private String serialize(Document document) {
        try {
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException e) {
            throw new JmxDocumentException("Unable to serialize JMeter plan", e);
        }
    }
}
