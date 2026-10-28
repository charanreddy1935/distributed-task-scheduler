# NOTE: The jar is built with the local Maven toolchain (mvn package -DskipTests)
# before `docker compose up --build`. Building Maven inside the container requires
# network access to Maven Central from the Docker build context, which is not
# available in all environments (e.g. DNS-restricted setups).
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY target/job-platform-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
