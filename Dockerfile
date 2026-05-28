# ===== 轻量 Runtime 镜像（整仓上下文，易被 .dockerignore 排除 target/）=====
# 推荐改用: fusioncareer-biz/Dockerfile.prod + 见 deploy/DEPLOY-JAVA.md
# ./mvnw package -DskipTests -pl fusioncareer-biz -am
# docker build -f fusioncareer-biz/Dockerfile.prod -t fusioncareer-backend:prod fusioncareer-biz
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY fusioncareer-biz/target/*.jar app.jar

RUN mkdir -p /data/fusioncareer/uploads

ENV JAVA_OPTS="-Xms256m -Xmx512m"
EXPOSE 9100

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar --spring.profiles.active=prod"]
