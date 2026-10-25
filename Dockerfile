FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS dependencies
WORKDIR /src
ENV MAVEN_OPTS="-Xmx384m -XX:MaxMetaspaceSize=192m"
COPY pom.xml .
RUN mvn -B dependency:go-offline

FROM dependencies AS test
COPY src ./src
RUN mvn -B verify

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime
RUN apk add --no-cache 'libexpat>=2.9.0-r0' 'brotli-libs>=1.2.1-r0' \
    && addgroup -g 10001 app && adduser -D -u 10001 -G app app
WORKDIR /app
COPY --from=test --chown=10001:10001 /src/target/orders-1.0.0.jar app.jar
COPY --chown=10001:10001 certs /app/certs
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
