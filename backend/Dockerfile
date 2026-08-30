# 多阶段构建：Maven 编译 Spring Boot 应用 → JRE 运行时镜像运行
# 注意：pom.xml 的 maven-enforcer 强制 JDK 17，故构建镜像必须使用 temurin-17

# ---------- 1. 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先拷 pom.xml，利用层缓存下载依赖
COPY pom.xml ./
RUN mvn -B dependency:go-offline

# 拷源码并打包（跳过测试）
COPY src ./src
RUN mvn -B -DskipTests package

# ---------- 2. 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 产物 jar 名由 pom 的 artifactId(seckill) + version(0.0.1-SNAPSHOT) 决定
COPY --from=build /app/target/seckill-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
