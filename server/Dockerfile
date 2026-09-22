FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -B package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/ocr-server.jar /app/ocr-server.jar
ENV PORT=8080
CMD ["java", "-jar", "/app/ocr-server.jar"]
