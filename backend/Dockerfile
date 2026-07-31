FROM eclipse-temurin:17-jdk-alpine AS build

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
COPY backend/src/ backend/src/
RUN backend/mvnw -B -ntp -f pom.xml -pl backend -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S vulnflow \
    && adduser -S vulnflow -G vulnflow \
    && mkdir -p /var/lib/vulnflow/reports \
    && chown -R vulnflow:vulnflow /var/lib/vulnflow
WORKDIR /app
COPY --from=build /workspace/backend/target/vulnflow-backend-*.jar app.jar
USER vulnflow

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
