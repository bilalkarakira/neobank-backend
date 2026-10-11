# The card network and the acquirer (two programs, one image). Build context: the root of this repository.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY cardnet/ cardnet/
RUN cd cardnet && mvn -q -B -Dmaven.test.skip=true package \
    && cp network/target/network-*-SNAPSHOT.jar /network.jar && cp acquirer/target/acquirer-*-SNAPSHOT.jar /acquirer.jar

FROM eclipse-temurin:21-jre
# the same user id in every image, so the three containers can all write to the shared clearing folder
RUN useradd --system --uid 10001 --create-home app && mkdir -p /data/clearing && chown app /data/clearing
COPY --from=build /network.jar /app/network.jar
COPY --from=build /acquirer.jar /app/acquirer.jar
USER app
# the compose file picks which one runs: java -jar /app/network.jar  or  java -jar /app/acquirer.jar
ENTRYPOINT ["java", "-jar"]
CMD ["/app/network.jar"]
