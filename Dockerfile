# Build stage: compile and package, with the test suite as the gate.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# `verify` rather than `package`: the 100% line and branch coverage gate runs here,
# so an image is never built from a tree that fails it.
RUN mvn -B clean verify

# Runtime stage: the agent plus the JMeter it shells out to.
FROM eclipse-temurin:21-jre-jammy

ARG JMETER_VERSION=5.6.3

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# JMeter is not a library here — the agent runs it as a subprocess and reads the
# .jtl it writes, so the real distribution has to be on the image.
RUN curl -fsSL -o /tmp/jmeter.tgz \
        "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
    && tar -xzf /tmp/jmeter.tgz -C /opt \
    && mv "/opt/apache-jmeter-${JMETER_VERSION}" /opt/apache-jmeter \
    && rm /tmp/jmeter.tgz

# JDBC drivers for SQL mode go here. A JDBC plan fails at connection time without
# one, and no amount of re-prompting can fix that from inside the test plan.
#   COPY drivers/*.jar /opt/apache-jmeter/lib/

# Unprivileged: the agent executes model-authored test plans, so it should not be
# root when it does.
RUN useradd --system --create-home --uid 10001 agent \
    && mkdir -p /var/lib/jmeter-agent/workspace \
    && chown -R agent:agent /var/lib/jmeter-agent

COPY --from=build /build/target/autonomous-jmeter-agent-*.jar /app/agent.jar

USER agent
WORKDIR /var/lib/jmeter-agent

ENV JMETER_HOME=/opt/apache-jmeter/bin \
    AGENT_WORKSPACE=/var/lib/jmeter-agent/workspace \
    JAVA_OPTS=""

# Artifacts outlive the container only if this is mounted.
VOLUME ["/var/lib/jmeter-agent/workspace"]

# The control plane's port. Unused in CLI mode, which is the default.
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/agent.jar \"$@\"", "--"]
CMD []
