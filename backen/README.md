# 企业官网后端服务

Spring Boot 3.2 + MyBatis-Plus + H2 内存数据库

## 环境要求

- JDK 17+
- Maven 3.8+（或使用项目自带的 mvnw）

## 快速启动

```bash
# 安装 JDK 17（macOS）
brew install openjdk@17

# 设置 JAVA_HOME
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "/opt/homebrew/opt/openjdk@17")
export PATH="$JAVA_HOME/bin:$PATH"

# 安装 Maven
brew install maven

# 启动项目
cd backen
mvn spring-boot:run
```

## 接口列表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/health | 健康检查 |
| GET | /api/articles | 查询所有文章 |
| GET | /api/articles/{id} | 根据 ID 查询 |
| POST | /api/articles | 新增文章 |
| DELETE | /api/articles/{id} | 删除文章 |

## 数据库

默认使用 H2 内存数据库，启动即可用，无需额外安装。  
H2 控制台：http://localhost:8080/h2-console  
JDBC URL：`jdbc:h2:mem:webdb`，用户名 `sa`，密码为空。

如需切换 MySQL，修改 `application.yml` 中的 datasource 配置即可。
