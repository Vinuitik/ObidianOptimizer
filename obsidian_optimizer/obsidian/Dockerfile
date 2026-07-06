FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# pg_dump/pg_restore for DB backup+restore to Drive. Must match the server major
# (Postgres 18 via paradedb); Alpine's postgresql18-client provides exactly that.
# Bump this alongside any Postgres-major upgrade of the `postgres` service.
RUN apk add --no-cache postgresql18-client
COPY --from=build /app/target/*.war app.war
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.war"]
