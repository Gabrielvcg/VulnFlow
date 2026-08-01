FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e AS trivy

FROM eclipse-temurin:17-jre-jammy
ARG AGENT_UID=10001
ARG AGENT_GID=10001
RUN groupadd --gid ${AGENT_GID} vulnflow-agent \
    && useradd --uid ${AGENT_UID} --gid ${AGENT_GID} --no-create-home --shell /usr/sbin/nologin vulnflow-agent \
    && mkdir -p /var/lib/vulnflow-agent/tmp /etc/vulnflow-agent \
    && chown -R vulnflow-agent:vulnflow-agent /var/lib/vulnflow-agent
COPY --from=trivy /usr/local/bin/trivy /usr/local/bin/trivy
COPY --from=build /workspace/target/vulnflow-agent-0.4.1.jar /opt/vulnflow-agent/vulnflow-agent.jar
USER vulnflow-agent:vulnflow-agent
VOLUME ["/var/lib/vulnflow-agent"]
ENTRYPOINT ["java", "-jar", "/opt/vulnflow-agent/vulnflow-agent.jar"]
