# NeoBank from a jar you built yourself (cd neobank-backend && ./mvnw package -DskipTests): no compiling inside Docker.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home app && mkdir -p /data/clearing && chown app /data/clearing
COPY neobank-backend/target/neobank-0.0.1-SNAPSHOT.jar /app/neobank.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/neobank.jar"]
