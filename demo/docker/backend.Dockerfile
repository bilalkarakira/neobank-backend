# NeoBank (the card issuer and the bank). Build context: the root of this repository.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY cardnet/ cardnet/
COPY pom.xml backend/pom.xml
COPY src/ backend/src/
# the issuer needs the ISO 8583 codec (and its clearing files) from the cardnet folder
RUN cd cardnet && mvn -q -B -Dmaven.test.skip=true -pl iso8583 -am install
RUN cd backend && mvn -q -B -Dmaven.test.skip=true package && cp target/neobank-*.jar /neobank.jar

FROM eclipse-temurin:21-jre
# the same user id in every image, so the three containers can all write to the shared clearing folder
RUN useradd --system --uid 10001 --create-home app && mkdir -p /data/clearing && chown app /data/clearing
COPY --from=build /neobank.jar /app/neobank.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/neobank.jar"]
