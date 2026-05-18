# ===== 轻量 Runtime 镜像 =====
# 使用方式：先在本地 mvn package，再 docker build
# cd FusionCareer-Backend && ./mvnw package -DskipTests -pl fusioncareer-biz -am
# docker build -t fusioncareer-backend:latest .
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY fusioncareer-biz/target/*.jar app.jar

RUN mkdir -p /data/fusioncareer/uploads

ENV JAVA_OPTS="-Xms256m -Xmx512m"
EXPOSE 9100

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar --spring.profiles.active=prod"]
