# NeoBank from a jar you built yourself (./mvnw package -DskipTests in the repository root): no compiling inside Docker.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home app && mkdir -p /data/clearing && chown app /data/clearing
COPY target/neobank-0.0.1-SNAPSHOT.jar /app/neobank.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/neobank.jar"]
