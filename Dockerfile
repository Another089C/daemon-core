# Etapa 1: Construcción
FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY --chown=gradle:gradle . .
RUN gradle bootJar --no-daemon

# Etapa 2: Ejecución
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Exponer el puerto del servidor
EXPOSE 8080

# Ejecutar Daemon
ENTRYPOINT ["java", "-jar", "app.jar"]