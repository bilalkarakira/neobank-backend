# The network and the acquirer from jars you built yourself (cd cardnet && ./mvnw package -DskipTests).
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home app && mkdir -p /data/clearing && chown app /data/clearing
COPY cardnet/network/target/network-0.1.0-SNAPSHOT.jar /app/network.jar
COPY cardnet/acquirer/target/acquirer-0.1.0-SNAPSHOT.jar /app/acquirer.jar
USER app
ENTRYPOINT ["java", "-jar"]
CMD ["/app/network.jar"]
