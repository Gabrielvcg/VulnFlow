FROM eclipse-temurin:17-jdk-alpine@sha256:638937c54b6d63f0973a20501973e7c433a36b1f22262bd2b25afa7be5ff8c4a AS build

WORKDIR /workspace
COPY backend/.mvn/ backend/.mvn/
COPY backend/mvnw backend/mvnw
COPY pom.xml ./
COPY processing-core/pom.xml processing-core/pom.xml
COPY aws/aws-adapters/pom.xml aws/aws-adapters/pom.xml
COPY aws/lambda-processor/pom.xml aws/lambda-processor/pom.xml
COPY backend/pom.xml backend/pom.xml
COPY agent/pom.xml agent/pom.xml
RUN chmod +x backend/mvnw && backend/mvnw -B -ntp -f pom.xml -pl backend -am dependency:go-offline
COPY processing-core/src/ processing-core/src/
COPY aws/aws-adapters/src/ aws/aws-adapters/src/
COPY aws/lambda-processor/src/ aws/lambda-processor/src/
COPY backend/src/ backend/src/
RUN backend/mvnw -B -ntp -f pom.xml -pl backend -am -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-alpine@sha256:02320dd4ce20e243dfb915c686089cf9315c763084fafbb12d5c9993aee18b57

ARG AWS_SIGNING_HELPER_VERSION=1.8.4
ARG AWS_SIGNING_HELPER_SHA256=b7568acd6e1517a4e1adaee68d52bfd6284a0e5305677166cd83d43a07c815c9

RUN apk add --no-cache ca-certificates gcompat libstdc++ \
    && wget -q \
      "https://rolesanywhere.amazonaws.com/releases/${AWS_SIGNING_HELPER_VERSION}/X86_64/Linux/Amzn2023/aws_signing_helper" \
      -O /tmp/aws_signing_helper \
    && echo "${AWS_SIGNING_HELPER_SHA256}  /tmp/aws_signing_helper" | sha256sum -c - \
    && install -m 0755 /tmp/aws_signing_helper /usr/local/bin/aws_signing_helper \
    && rm -f /tmp/aws_signing_helper \
    && addgroup -S vulnflow \
    && adduser -S vulnflow -G vulnflow \
    && mkdir -p /var/lib/vulnflow/reports \
    && chown -R vulnflow:vulnflow /var/lib/vulnflow
WORKDIR /app
COPY --from=build /workspace/backend/target/vulnflow-backend-*.jar app.jar
USER vulnflow

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
