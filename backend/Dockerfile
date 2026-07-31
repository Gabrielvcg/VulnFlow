FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S vulnflow \
    && adduser -S vulnflow -G vulnflow \
    && mkdir -p /var/lib/vulnflow/reports \
    && chown -R vulnflow:vulnflow /var/lib/vulnflow
WORKDIR /app
COPY --from=build /workspace/target/vulnflow-backend-*.jar app.jar
USER vulnflow

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
