# Build stage: compile the Maven multi-module project and extract the Spring Boot layered jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY student-service/pom.xml student-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY records-service/pom.xml records-service/pom.xml
COPY timetable-service/pom.xml timetable-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline || true

COPY common common
COPY student-service student-service
COPY payment-service payment-service
COPY records-service records-service
COPY timetable-service timetable-service
COPY gateway gateway
RUN --mount=type=cache,target=/root/.m2 mvn -q package -DskipTests

ARG MODULE
RUN cp /build/${MODULE}/target/*.jar /app.jar

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
