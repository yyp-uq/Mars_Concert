# 火星演唱会平台 · 手把手搭建手册（第 1 卷）
## 从 0 到 1：JDK → Maven 工程 → Spring Boot 三层 → MySQL → 现有前端联调

> 本手册的目标：你**完全照着做**，不需要任何前置 Spring 经验，结束时将得到：
> - 一个能在 IDEA 里启动、浏览器访问 `http://localhost:8080/api/v1/ping` 返回 JSON 的 Spring Boot 3 工程；
> - 一套可复用的「统一返回 / 统一异常 / 参数校验 / 接口文档 / 跨域」基础设施；
> - 一个**完整业务闭环**：FAQ 表的增删改查（对应你前端 `pages/artist.html` 里那 6 条常见问题）；
> - 前端 `artist.html` 从「写死 6 条」改成「调用后端接口动态渲染」。
>
> 约定：
> - 所有命令区分「PowerShell」（Windows 终端）；
> - 每个文件都给出 **完整路径 + 完整代码 + 为什么这么写**；
> - 包名统一用 `com.mars.concert`；工程放在 `C:\Users\23081\Desktop\Mars_Concert\mars-platform`。
> - 版本：**JDK 17 + Spring Boot 3.3.5 + MyBatis-Plus 3.5.7 + MySQL 8**（Spring Boot 3 必须 JDK17+，也为将来 Spring AI 铺路）。

---

## 目录

- [第 0 步：先搞懂一个 HTTP 请求在本项目里的旅程](#第-0-步先搞懂一个-http-请求在本项目里的旅程)
- [第 1 步：安装并验证 JDK 17](#第-1-步安装并验证-jdk-17)
- [第 2 步：安装并验证 Maven](#第-2-步安装并验证-maven)
- [第 3 步：准备 MySQL 并建库建表](#第-3-步准备-mysql-并建库建表)
- [第 4 步：手动创建 Maven 工程目录与 pom.xml](#第-4-步手动创建-maven-工程目录与-pomxml)
- [第 5 步：写启动类与全局配置文件](#第-5-步写启动类与全局配置文件)
- [第 6 步：工程包结构总览](#第-6-步工程包结构总览)
- [第 7 步：通用基础设施——统一返回 Result](#第-7-步通用基础设施统一返回-result)
- [第 8 步：通用基础设施——错误码与异常体系](#第-8-步通用基础设施错误码与异常体系)
- [第 9 步：通用基础设施——全局异常处理器](#第-9-步通用基础设施全局异常处理器)
- [第 10 步：通用基础设施——Web 配置（跨域/JSON/参数解析）](#第-10-步通用基础设施web-配置跨域json参数解析)
- [第 11 步：集成 MyBatis-Plus 与插件配置](#第-11-步集成-mybatis-plus-与插件配置)
- [第 12 步：接口文档 Knife4j（OpenAPI3）](#第-12-步接口文档-knife4jopenapi3)
- [第 13 步：业务闭环①——实体类 Faq](#第-13-步业务闭环实体类-faq)
- [第 14 步：业务闭环②——Mapper 层](#第-14-步业务闭环mapper-层)
- [第 15 步：业务闭环③——DTO/VO/Query 模型](#第-15-步业务闭环dtovoquery-模型)
- [第 16 步：业务闭环④——Service 层](#第-16-步业务闭环service-层)
- [第 17 步：业务闭环⑤——Controller 层（处理 HTTP）](#第-17-步业务闭环controller-层处理-http)
- [第 18 步：启动并用 Knife4j / Apifox 测试全部接口](#第-18-步启动并用-knife4j--apifox-测试全部接口)
- [第 19 步：把前端静态站点交给 Spring Boot 托管](#第-19-步把前端静态站点交给-spring-boot-托管)
- [第 20 步：前端封装 api.js 并改造 artist.html](#第-20-步前端-apijs-并改造-artisthtml)
- [第 21 步：端到端联调与常见报错排查表](#第-21-步端到端联调与常见报错排查表)
- [本卷小结与下一卷预告](#本卷小结与下一卷预告)

---

## 第 0 步：先搞懂一个 HTTP 请求在本项目里的旅程

在写代码前，先建立「分层」的心智模型。当前端发起：

```
GET http://localhost:8080/api/v1/faqs?category=SHOW&pageNum=1&pageSize=10
```

请求在 Spring Boot 内部依次经过：

```
浏览器/前端
   │  HTTP 请求（URL + 方法 + 请求头 + JSON 请求体）
   ▼
[1] Servlet 容器(Tomcat，SpringBoot内置) 接收 TCP/HTTP
   ▼
[2] DispatcherServlet（SpringMVC 前端控制器，框架自带）统一分发
   ▼
[3] Filter / Interceptor 链：跨域处理、编码、（以后的登录JWT校验）
   ▼
[4] Controller 方法   ←— @RestController + @GetMapping，把 URL 映射到方法
   │     · @RequestParam 把 query 参数绑定到方法参数
   │     · @RequestBody 把 JSON 反序列化为 DTO 对象
   │     · @Valid 触发参数校验
   ▼
[5] Service（接口）→ ServiceImpl（业务逻辑）
   │     · 编排业务：校验、计算、调用多个 Mapper、加事务
   ▼
[6] Mapper（MyBatis-Plus）→ 拼 SQL → 通过 JDBC 发给 MySQL
   ▼
MySQL 返回数据行（Entity 实体）
   ▲ 原路返回：Entity → 转成 VO（给前端的视图对象）→ Controller return
   ▼
[7] 方法返回值被 HttpMessageConverter 用 Jackson 序列化为 JSON
   ▼
[8] 统一包成 Result<T> → 响应体 {code,message,data} → 浏览器
```

**为什么要分 Controller/Service/Mapper 三层？**
- Controller 只关心「Web」：收参数、校验、调 Service、返回结果，不写业务；
- Service 只关心「业务规则」：不关心是 HTTP 还是定时任务在调它，方便复用和测试；
- Mapper 只关心「数据库」：和表一一对应。
- 这样任何一层改动不波及其它层，也是将来按域拆微服务的基础。

**DTO / Entity / VO 为什么分三个对象？**
- `Entity`：和数据库表一一对应（Faq）；
- `DTO`：前端**传进来**的参数（新增/修改表单 FaqSaveDTO），不暴露表结构、可控制必填；
- `VO`：后端**返回去**的视图数据（FaqVO），可拼装多表字段、隐藏敏感字段。
- 三者分开能避免「前端多传一个 `id`/`status` 就被直接写库」的安全问题。

---

## 第 1 步：安装并验证 JDK 17

### 1.1 下载安装
- 推荐 **Eclipse Temurin JDK 17 (LTS)**：https://adoptium.net/temurin/releases/?version=17
- Windows x64 选 `.msi`，安装时勾选 **"Set JAVA_HOME variable"** 和 **"Add to PATH"**。

### 1.2 验证
打开**新的** PowerShell 窗口（改完环境变量必须重开）：

```powershell
java -version
```

应看到类似：
```
openjdk version "17.0.12" 2024-07-16
OpenJDK Runtime Environment Temurin-17.0.12+7
```

再确认 JAVA_HOME：
```powershell
echo $env:JAVA_HOME
```
应类似 `C:\Program Files\Eclipse Adoptium\jdk-17.0.12.7-hotspot\`。

> **为什么必须 17 而不是 8？** Spring Boot 3.x / Spring 6 / Spring AI 的字节码基线是 Java 17，用 JDK 8 连启动都起不来。

### 1.3 IDEA 配置
`File → Project Structure → Project SDK` 选 17；`Language level` 选 17。

---

## 第 2 步：安装并验证 Maven

### 2.1 下载
- https://maven.apache.org/download.cgi 下 `apache-maven-3.9.x-bin.zip`，解压到例如 `D:\dev\apache-maven-3.9.9`（路径不要有中文/空格）。

### 2.2 配置环境变量
- 新建系统变量 `MAVEN_HOME = D:\dev\apache-maven-3.9.9`
- 编辑 `Path`，追加 `%MAVEN_HOME%\bin`

### 2.3 配置国内镜像（非常重要，否则下依赖极慢）
编辑 `D:\dev\apache-maven-3.9.9\conf\settings.xml`，在 `<mirrors>` 内加入阿里云镜像：

```xml
<mirror>
  <id>aliyun-public</id>
  <mirrorOf>*</mirrorOf>
  <name>aliyun public</name>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

可选：指定本地仓库目录（避免占用 C 盘），改 `<localRepository>`：
```xml
<localRepository>D:\dev\maven-repo</localRepository>
```

### 2.4 验证（重开 PowerShell）
```powershell
mvn -v
```
应显示 `Apache Maven 3.9.x` 与 `Java version: 17`。

> IDEA 中也要指定该 Maven：`Settings → Build,Execution,Deployment → Build Tools → Maven`：
> - Maven home path：`D:\dev\apache-maven-3.9.9`
> - User settings file：勾 Override 选你的 `settings.xml`。

---

## 第 3 步：准备 MySQL 并建库建表

### 3.1 安装 MySQL 8
用官方安装包或 Docker。已有 MySQL 可跳过。Docker 方式（可选）：
```powershell
docker run --name mars-mysql -e MYSQL_ROOT_PASSWORD=root123 -p 3306:3306 -d mysql:8.0
```

### 3.2 用 IDEA 数据库窗口或 Navicat / 命令行连接后执行建库脚本

> **为什么字符集用 utf8mb4？** `utf8` 在 MySQL 里最多 3 字节，存不了 emoji 和部分生僻字；`utf8mb4` 才是真正的 UTF-8，评论/昵称里有表情必须用它。

```sql
CREATE DATABASE IF NOT EXISTS mars_concert
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE mars_concert;

-- FAQ 常见问题表（内容域，对应前端 artist.html 的 6 条问答）
CREATE TABLE ct_faq (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    category     VARCHAR(32)  NOT NULL DEFAULT 'GENERAL'
                 COMMENT '分类: SHOW演出/SETLIST歌单/TICKET购票/ENTRY入场/GENERAL通用',
    question_en  VARCHAR(255) NOT NULL COMMENT '英文问题(当前前端是英文界面)',
    question_zh  VARCHAR(255)          DEFAULT NULL COMMENT '中文问题(预留双语)',
    answer       MEDIUMTEXT   NOT NULL COMMENT '答案(可含简单HTML)',
    sort_no      INT          NOT NULL DEFAULT 0 COMMENT '排序,越小越靠前',
    view_count   INT          NOT NULL DEFAULT 0 COMMENT '浏览次数(业务字段演示)',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                 ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    create_by    VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    update_by    VARCHAR(64)           DEFAULT NULL COMMENT '更新人',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常1删除',
    version      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_category_status (category, status),
    KEY idx_sort (sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='常见问题表';

-- 初始化 6 条数据（与你现在前端写死的内容一致，先真实入库）
INSERT INTO ct_faq(category, question_en, question_zh, answer, sort_no, status) VALUES
('SHOW','When and where is Mars Concert 2.0?','演出时间地点？',
 'September 25–27 and October 2, 3, 5, 6, 2026 — seven shows — at Nanjing Tangshan Music Valley, a 400,000 ㎡ abandoned quarry.',
 1,1),
('SETLIST','What is the "Twin Setlist"?','什么是双生歌单？',
 'Two complete setlists, each with its own narrative arc, mirroring each other at the core and alternating across the seven shows.',
 2,1),
('TICKET','Matinee vs. evening — what''s the difference?','日场和晚场区别？',
 'One ticket grants entry to both. The matinee is unplugged and relaxed; the evening show is the full rock live narrative.',
 3,1),
('ENTRY','Is it really all-standing?','是全站票吗？',
 'Yes — an all-standing rock festival format. Wear comfortable shoes and arrive early to claim your spot near the stage.',
 4,1),
('SHOW','Who is the guest?','嘉宾是谁？',
 'Zuo Li — guitarist and Hua Chenyu''s best friend since Super Boy 2013. He joins the matinee unplugged segment.',
 5,1),
('TICKET','Where can I buy tickets?','在哪里购票？',
 'Official platforms: Damai, Maoyan, Ctrip and Douyin. Four tiers: ¥380 / ¥680 / ¥980 / ¥1280. One ticket covers both shows.',
 6,1);
```

> 字段设计原因：`create_time/update_time/deleted/version` 是全项目每张表的「标配四件套」——逻辑删除（不物理删数据，可恢复、可审计）+ 乐观锁（并发更新不互相覆盖）。

---

## 第 4 步：手动创建 Maven 工程目录与 pom.xml

> 你要「手动创建」，所以我们不用 IDEA 的 Spring Initializr 向导，手工搭，理解每个文件的意义。

### 4.1 建目录

在 `C:\Users\23081\Desktop\Mars_Concert\` 下新建文件夹 `mars-platform`，最终结构（先把空文件夹建好，Java 包就是嵌套文件夹）：

```
mars-platform/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/mars/concert/
        │       ├── MarsConcertApplication.java
        │       ├── common/
        │       │   ├── result/
        │       │   ├── exception/
        │       │   ├── enums/
        │       │   └── config/
        │       └── content/
        │           ├── controller/
        │           ├── service/
        │           │   └── impl/
        │           ├── mapper/
        │           ├── entity/
        │           └── model/
        │               ├── dto/
        │               ├── vo/
        │               └── query/
        └── resources/
            ├── application.yml
            └── static/            ← 第19步放前端
```

Windows 快速建目录（PowerShell，可选执行）：
```powershell
cd C:\Users\23081\Desktop\Mars_Concert
mkdir mars-platform\src\main\java\com\mars\concert\common\result,
      mars-platform\src\main\java\com\mars\concert\common\exception,
      mars-platform\src\main\java\com\mars\concert\common\enums,
      mars-platform\src\main\java\com\mars\concert\common\config,
      mars-platform\src\main\java\com\mars\concert\content\controller,
      mars-platform\src\main\java\com\mars\concert\content\service\impl,
      mars-platform\src\main\java\com\mars\concert\content\mapper,
      mars-platform\src\main\java\com\mars\concert\content\entity,
      mars-platform\src\main\java\com\mars\concert\content\model\dto,
      mars-platform\src\main\java\com\mars\concert\content\model\vo,
      mars-platform\src\main\java\com\mars\concert\content\model\query,
      mars-platform\src\main\resources\static
```

然后用 IDEA：`File → Open` 选择 `mars-platform` 文件夹（打开含 pom.xml 的目录），IDEA 识别为 Maven 工程。

### 4.2 写 `pom.xml`

路径：`mars-platform/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- ① 继承 Spring Boot 官方父工程：统一管理成百上千个依赖的版本号 -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.mars</groupId>
    <artifactId>mars-platform</artifactId>
    <version>1.0.0</version>
    <name>mars-platform</name>
    <description>Mars Concert ticketing platform</description>

    <properties>
        <!-- ② 统一锁版本，将来升级只改这里 -->
        <java.version>17</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <knife4j.version>4.5.0</knife4j.version>
    </properties>

    <dependencies>
        <!-- ③ Web 起步器：内含 Spring MVC + 内嵌 Tomcat + Jackson，写 HTTP 接口必备 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- ④ 参数校验：@NotBlank/@NotNull/@Min 等，Controller 里 @Valid 才生效 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- ⑤ MyBatis-Plus（注意是 boot3 专用 artifact）：单表 CRUD/分页/逻辑删除免写 SQL -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>

        <!-- ⑥ MySQL 驱动：版本由 spring-boot-starter-parent 管理，不用写 version -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- ⑦ Lombok：@Data 自动生成 getter/setter/toString，编译期生效 -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- ⑧ Knife4j：增强版 Swagger UI，自动根据代码生成可视化接口文档 -->
        <dependency>
            <groupId>com.github.xiaoymin</groupId>
            <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
            <version>${knife4j.version}</version>
        </dependency>

        <!-- ⑨ 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- ⑩ 打包插件：mvn package 出可直接 java -jar 运行的 fat jar -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

**逐项说明（为什么需要）**
- `parent`：继承后所有 `starter-*` 不用写版本号，且经过官方兼容性测试，避免版本冲突。
- `starter-web`：一个依赖把 Spring MVC、Tomcat、Jackson、校验前的基础都拉进来。
- `mybatis-plus-spring-boot3-starter`：Spring Boot 3 是 `jakarta.*` 包名，必须用这个 boot3 专用包；用老的 `mybatis-plus-boot-starter` 会报类找不到。
- `mysql-connector-j`：JDBC 驱动，运行期才需要，故 `runtime`。
- Lombok：IDEA 需要装 Lombok 插件并开启 `Settings → Build → Compiler → Annotation Processors → Enable annotation processing`。
- Knife4j 4.x 对应 OpenAPI3 + jakarta，和 Boot3 匹配。

写完 pom，IDEA 会提示 `Load Maven Project`，点刷新，等待依赖下载完成（右下角进度条结束、无红色报错）。

---

## 第 5 步：写启动类与全局配置文件

### 5.1 启动类
路径：`src/main/java/com/mars/concert/MarsConcertApplication.java`

```java
package com.mars.concert;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 * @SpringBootApplication 是一个组合注解：
 *   - @SpringBootConfiguration：标记这是一个配置类
 *   - @EnableAutoConfiguration：开启自动装配（根据 pom 里的依赖自动配好 Tomcat、数据源、MyBatis 等）
 *   - @ComponentScan：扫描本类所在包及子包中的 @Controller/@Service/@Component
 * @MapperScan：告诉 MyBatis-Plus 去哪个包找 Mapper 接口，自动为其生成代理实现类
 */
@SpringBootApplication
@MapperScan("com.mars.concert.**.mapper")
public class MarsConcertApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarsConcertApplication.class, args);
    }
}
```

> **为什么 Mapper 要 `@MapperScan`？** Mapper 是接口没有实现类，MyBatis 在启动时用动态代理为其生成实现并放进 Spring 容器，不扫描会报「找不到 Bean」。`**.mapper` 表示各业务域下的 mapper 包都扫描，为以后多域做准备。

### 5.2 全局配置
路径：`src/main/resources/application.yml`

```yaml
server:
  port: 8080                 # 服务端口
  servlet:
    context-path: /          # 访问根路径

spring:
  application:
    name: mars-platform
  # ---------- 数据源 ----------
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver   # MySQL 8 驱动类（注意 cj）
    url: jdbc:mysql://localhost:3306/mars_concert?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: root123        # 改成你自己的密码
  # ---------- JSON 序列化 ----------
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: Asia/Shanghai
    default-property-inclusion: non_null          # null 字段不返回给前端，JSON 更干净

# ---------- MyBatis-Plus ----------
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true            # 下划线列名 ↔ 驼峰字段：question_en ↔ questionEn
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl   # 控制台打印SQL，学习期非常有用
  global-config:
    db-config:
      id-type: auto            # 主键自增（和表 AUTO_INCREMENT 对应）
      logic-delete-field: deleted   # 逻辑删除字段名
      logic-delete-value: 1
      logic-not-delete-value: 0

# ---------- 日志 ----------
logging:
  level:
    com.mars.concert: debug    # 自己的代码打印 debug，便于观察
```

**关键原因**
- URL 上 `serverTimezone=Asia/Shanghai`：MySQL 8 连接必须带时区否则报错；`allowPublicKeyRetrieval=true`：MySQL 8 默认密码插件用 caching_sha2，首次连接需要它。
- `map-underscore-to-camel-case`：数据库习惯 `question_en`，Java 习惯 `questionEn`，自动映射省去手写别名。
- 打印 SQL 让你清楚每条查询长什么样，熟练后可关掉。

---

## 第 6 步：工程包结构总览

建好后你的包结构（标注每层职责）：

```
com.mars.concert
├── MarsConcertApplication.java        启动入口
├── common                             【公共层，与具体业务无关，可复用】
│   ├── result/Result.java             统一响应包装
│   ├── exception/
│   │   ├── BusinessException.java     业务异常
│   │   └── GlobalExceptionHandler.java 全局异常兜底
│   ├── enums/
│   │   └── ErrorCode.java             错误码接口（本卷先用枚举直接实现）
│   └── config/
│       ├── WebMvcConfig.java          跨域/拦截器/JSON 等 Web 配置
│       ├── MybatisPlusConfig.java     分页插件等
│       └── MetaObjectHandler.java     create_time/update_time 自动填充
└── content                            【内容业务域】
    ├── controller/FaqController.java  接收 HTTP
    ├── service/
    │   ├── FaqService.java            业务接口
    │   └── impl/FaqServiceImpl.java   业务实现
    ├── mapper/FaqMapper.java          数据库访问
    ├── entity/Faq.java                表实体
    └── model/
        ├── dto/FaqSaveDTO.java        入参（新增/修改）
        ├── query/FaqQuery.java        入参（分页查询条件）
        └── vo/FaqVO.java              出参（返回前端）
```

> 「common 放和业务无关的，content 放 FAQ 业务」——以后加会员域就再建一个 `member` 包，结构完全一样，互不干扰。

---

## 第 7 步：通用基础设施——统一返回 Result

路径：`common/result/Result.java`

```java
package com.mars.concert.common.result;

import com.mars.concert.common.enums.ErrorCode;
import lombok.Data;

/**
 * 全项目统一响应结构。
 * 前端只需要判断 code==0 即成功，失败时读 message。
 * { "code":0, "message":"success", "data":... }
 */
@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    // 成功（带数据）
    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    // 成功（无数据，如删除）
    public static <T> Result<T> success() {
        return success(null);
    }

    // 失败（用错误码枚举）
    public static <T> Result<T> fail(ErrorCode errorCode) {
        Result<T> r = new Result<>();
        r.code = errorCode.getCode();
        r.message = errorCode.getMessage();
        return r;
    }

    // 失败（自定义信息，用于参数校验等）
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
```

再建一个分页返回包装，路径：`common/result/PageResult.java`

```java
package com.mars.concert.common.result;

import lombok.Data;
import java.util.List;

/**
 * 分页返回统一结构。
 * 泛型 <T> 表示列表里装的可以是任意 VO。
 */
@Data
public class PageResult<T> {
    private List<T> list;      // 当前页数据
    private Long total;        // 总条数
    private Long pageNum;      // 当前页码
    private Long pageSize;     // 每页条数

    public static <T> PageResult<T> of(List<T> list, long total, long pageNum, long pageSize) {
        PageResult<T> p = new PageResult<>();
        p.list = list;
        p.total = total;
        p.pageNum = pageNum;
        p.pageSize = pageSize;
        return p;
    }
}
```

**为什么要统一返回？** 前端处理逻辑可以统一（拦截器里统一判断 code、统一弹错误），团队协作时接口格式永远一致。

---

## 第 8 步：通用基础设施——错误码与异常体系

### 8.1 错误码接口
路径：`common/enums/ErrorCode.java`

```java
package com.mars.concert.common.enums;

/**
 * 错误码规范：让每个业务模块实现自己的错误码枚举。
 * 本卷先定义接口 + 一个通用枚举。
 */
public interface ErrorCode {
    int getCode();
    String getMessage();
}
```

### 8.2 通用错误码枚举
路径：`common/enums/CommonErrorCode.java`

```java
package com.mars.concert.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码分段约定：
 * 0 成功；1xxx 通用/参数；2xxx 会员；3xxx 演出；4xxx 订单；5xxx 支付……
 */
@Getter
@AllArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    PARAM_INVALID(1001, "参数不合法"),
    RESOURCE_NOT_FOUND(1002, "资源不存在"),
    SYSTEM_ERROR(9999, "系统繁忙，请稍后再试"),
    UNAUTHORIZED(1401, "未登录或登录已过期"),
    FORBIDDEN(1403, "没有操作权限");

    private final int code;
    private final String message;
}
```

### 8.3 内容域错误码
路径：`content/model/...` 之外，放在 `com.mars.concert.content.enums.ContentErrorCode.java`
（请在 `content` 下新建 `enums` 文件夹）

```java
package com.mars.concert.content.enums;

import com.mars.concert.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ContentErrorCode implements ErrorCode {
    FAQ_NOT_FOUND(6001, "FAQ不存在或已下架");

    private final int code;
    private final String message;
}
```

### 8.4 业务异常
路径：`common/exception/BusinessException.java`

```java
package com.mars.concert.common.exception;

import com.mars.concert.common.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：业务规则不满足时抛出（如 FAQ 不存在）。
 * 它不是「程序出错」，而是「正常的业务拒绝」，所以用受检外的运行时异常，
 * 由全局异常处理器统一捕获并转成友好 JSON，而不是返回 500 白页。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

> **为什么不用 try-catch 到处返回错误？** 业务里随时可能失败，层层 try-catch 会让代码极其臃肿；抛异常 + 统一处理器横切处理，主流程只写「成功路径」，干净且不会漏处理。

---

## 第 9 步：通用基础设施——全局异常处理器

路径：`common/exception/GlobalExceptionHandler.java`

```java
package com.mars.concert.common.exception;

import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 * 表示：拦截所有 @RestController 抛出的异常，并把返回值写成 JSON。
 * 不同异常走不同 @ExceptionHandler 方法，做到「精准提示，且不把堆栈暴露给用户」。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：可预期的拒绝，原样返回错误码与提示 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** @RequestBody + @Valid 校验失败（JSON 表单） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** 表单对象绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** 单个参数 @RequestParam 上加 @NotBlank 等校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(), e.getMessage());
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(),
                "缺少参数: " + e.getParameterName());
    }

    /** 请求体 JSON 格式错误/为空 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleBody(HttpMessageNotReadableException e) {
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(), "请求体格式错误");
    }

    /** 请求方法不对（接口只接受 POST 却用了 GET） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(),
                "不支持的请求方法: " + e.getMethod());
    }

    /** 兜底：所有未预料异常，记录完整堆栈，但只给用户返回友好提示 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(CommonErrorCode.SYSTEM_ERROR);
    }
}
```

**为什么兜底要记日志但不返回堆栈？** 堆栈可能暴露表名、SQL、包结构等敏感信息，是安全隐患；但开发排错又必须看堆栈，所以「服务端日志记全、给客户端只回友好文案」。

---

## 第 10 步：通用基础设施——Web 配置（跨域/JSON/参数解析）

路径：`common/config/WebMvcConfig.java`

```java
package com.mars.concert.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 实现 WebMvcConfigurer 来定制 SpringMVC 行为。
 * 当前只配 CORS；以后登录功能会在这里 addInterceptors() 注册 JWT 拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 跨域配置。
     * 开发时前端可能用 5500(VSCode Live Server) 或直接打开 file:// 页面，
     * 浏览器同源策略会拦截不同源的 AJAX，需要后端明确允许。
     * 生产环境前后端同源(都走 Nginx/8080)后，可把 allowedOriginPatterns 收窄为正式域名。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")                 // 只对接口放行跨域
                .allowedOriginPatterns("*")            // 允许任意来源（开发期）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")                   // 允许 Authorization 等自定义头
                .allowCredentials(true)                // 允许带 cookie
                .maxAge(3600);                         // 预检请求缓存1小时
    }
}
```

> 注意：以后如果用 Spring Security，CORS 也要在 Security 过滤器链里再开启一次（Security 在更外层），本卷暂不涉及。

---

## 第 11 步：集成 MyBatis-Plus 与插件配置

### 11.1 分页插件 + 乐观锁插件
路径：`common/config/MybatisPlusConfig.java`

```java
package com.mars.concert.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 * 不加分页插件，调用 Page 分页不会真的拼 LIMIT，会查出全表，务必配置。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件：指定数据库类型为 MySQL
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 乐观锁插件：更新时自动带 WHERE version=? 并 version+1
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
```

### 11.2 公共字段自动填充
路径：`common/config/MetaObjectHandler.java`

```java
package com.mars.concert.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 插入/更新时自动填充 create_time、update_time。
 * 这样业务代码不用每次 new 时间，统一由框架处理，避免漏填。
 * 将来接入登录后，create_by/update_by 可从当前登录用户上下文取值。
 */
@Component
public class MetaObjectHandler implements com.baomidou.mybatisplus.core.handlers.MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
```

---

## 第 12 步：接口文档 Knife4j（OpenAPI3）

路径：`common/config/Knife4jConfig.java`

```java
package com.mars.concert.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j 文档元信息。
 * 启动后访问 http://localhost:8080/doc.html 即可看到所有接口并在线调试。
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI marsOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("火星演唱会平台 API")
                .version("1.0.0")
                .description("Mars Concert Platform API Documentation")
                .contact(new Contact().name("mars")));
    }
}
```

在 `application.yml` 追加（可选，关闭生产环境文档用）：
```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
knife4j:
  enable: true
```

---

## 第 13 步：业务闭环①——实体类 Faq

路径：`content/entity/Faq.java`

```java
package com.mars.concert.content.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FAQ 实体，与表 ct_faq 一一对应。
 *
 * @TableName 指定表名；类名不叫 CtFaq 是因为包已经表明域，表前缀 ct_ 仅用于数据库分组。
 */
@Data
@TableName("ct_faq")
public class Faq {

    /** 主键。IdType.AUTO 对应数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类 */
    private String category;

    /** 英文问题（@TableField 可省略，因为已开启下划线转驼峰，这里写出来作演示） */
    @TableField("question_en")
    private String questionEn;

    private String questionZh;

    private String answer;

    private Integer sortNo;

    private Integer viewCount;

    /** 1上架 0下架 */
    private Integer status;

    /** 插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 插入和更新时都自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private String createBy;

    private String updateBy;

    /** 逻辑删除字段：MP 查询会自动追加 AND deleted=0，删除变 UPDATE */
    @TableLogic
    private Integer deleted;

    /** 乐观锁版本号：更新时自动校验并自增 */
    @Version
    private Integer version;
}
```

**注解原因**
- `@TableName`：类名与表名不一致时必须指定。
- `@TableLogic`：标记后 `deleteById` 实际执行 `UPDATE ct_faq SET deleted=1`；所有查询自动过滤已删除，无需手写。
- `@Version`：配合乐观锁插件，`updateById` 会变成 `... WHERE id=? AND version=?` 并 `version=version+1`，并发更新时后者自动失败，防止覆盖。
- `@TableField(fill=...)`：配合第 11 步的 MetaObjectHandler。

---

## 第 14 步：业务闭环②——Mapper 层

路径：`content/mapper/FaqMapper.java`

```java
package com.mars.concert.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.concert.content.entity.Faq;
import org.apache.ibatis.annotations.Mapper;

/**
 * 继承 BaseMapper<Faq> 即免费获得：
 * insert / deleteById / updateById / selectById / selectList(Wrapper) / selectPage ...
 * 复杂多表查询时，再在这里写自定义方法 + XML 或 @Select 注解。
 */
@Mapper
public interface FaqMapper extends BaseMapper<Faq> {
}
```

> **为什么接口没实现类却能用？** 启动时 MyBatis 用 JDK 动态代理为它生成实现对象并注册到 Spring 容器，注入到 Service 即可调用。

---

## 第 15 步：业务闭环③——DTO/VO/Query 模型

### 15.1 新增/修改入参
路径：`content/model/dto/FaqSaveDTO.java`

```java
package com.mars.concert.content.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增/修改 FAQ 的入参。
 * 修改时 id 单独走 URL，故 DTO 里不放 id，防止前端篡改其它字段。
 */
@Data
@Schema(description = "FAQ保存入参")
public class FaqSaveDTO {

    @Schema(description = "分类", example = "SHOW")
    @NotBlank(message = "分类不能为空")
    @Size(max = 32, message = "分类长度不能超过32")
    private String category;

    @Schema(description = "英文问题", example = "When and where is Mars Concert 2.0?")
    @NotBlank(message = "问题不能为空")
    @Size(max = 255, message = "问题过长")
    private String questionEn;

    @Schema(description = "中文问题")
    @Size(max = 255)
    private String questionZh;

    @Schema(description = "答案")
    @NotBlank(message = "答案不能为空")
    private String answer;

    @Schema(description = "排序，越小越靠前", example = "1")
    private Integer sortNo;

    @Schema(description = "状态 1上架 0下架", example = "1")
    private Integer status;
}
```

> `@NotBlank/@Size` 来自 `spring-boot-starter-validation`，必须在 Controller 参数上加 `@Valid` 才会触发，错误会被第 9 步的处理器捕获。

### 15.2 分页查询条件
路径：`content/model/query/FaqQuery.java`

```java
package com.mars.concert.content.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 列表查询条件（都是可选条件，所以不加校验注解）。
 */
@Data
@Schema(description = "FAQ分页查询条件")
public class FaqQuery {

    @Schema(description = "分类", example = "SHOW")
    private String category;

    @Schema(description = "问题关键词(模糊)")
    private String keyword;

    @Schema(description = "状态 1上架0下架，不传查全部", example = "1")
    private Integer status;

    @Schema(description = "页码", example = "1")
    private Long pageNum = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long pageSize = 10L;
}
```

### 15.3 返回视图对象
路径：`content/model/vo/FaqVO.java`

```java
package com.mars.concert.content.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 返回给前端的 FAQ 数据。
 * 故意不含 deleted 字段（前端无需知道逻辑删除标记），演示 VO 与 Entity 解耦。
 */
@Data
@Schema(description = "FAQ视图对象")
public class FaqVO {
    private Long id;
    private String category;
    private String questionEn;
    private String questionZh;
    private String answer;
    private Integer sortNo;
    private Integer viewCount;
    private Integer status;
    private LocalDateTime createTime;
}
```

---

## 第 16 步：业务闭环④——Service 层

### 16.1 接口
路径：`content/service/FaqService.java`

```java
package com.mars.concert.content.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mars.concert.content.entity.Faq;
import com.mars.concert.content.model.dto.FaqSaveDTO;
import com.mars.concert.content.model.query.FaqQuery;
import com.mars.concert.content.model.vo.FaqVO;
import com.mars.concert.common.result.PageResult;

/**
 * 继承 IService<Faq> 可获得 save/updateById/removeById/getById 等基础方法。
 * 自定义复杂业务方法在这里声明。
 */
public interface FaqService extends IService<Faq> {

    /** 新增 FAQ，返回新 id */
    Long createFaq(FaqSaveDTO dto);

    /** 修改 FAQ */
    void updateFaq(Long id, FaqSaveDTO dto);

    /** 删除（逻辑删除） */
    void deleteFaq(Long id);

    /** 详情（浏览量 +1） */
    FaqVO getFaqDetail(Long id);

    /** 分页条件查询 */
    PageResult<FaqVO> pageFaq(FaqQuery query);
}
```

### 16.2 实现类
路径：`content/service/impl/FaqServiceImpl.java`

```java
package com.mars.concert.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.result.PageResult;
import com.mars.concert.content.entity.Faq;
import com.mars.concert.content.enums.ContentErrorCode;
import com.mars.concert.content.mapper.FaqMapper;
import com.mars.concert.content.model.dto.FaqSaveDTO;
import com.mars.concert.content.model.query.FaqQuery;
import com.mars.concert.content.model.vo.FaqVO;
import com.mars.concert.content.service.FaqService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Service：交给 Spring 管理，Controller 才能注入。
 * extends ServiceImpl<FaqMapper, Faq>：获得通用 CRUD 实现，同时 this.list()/this.getById() 可用。
 */
@Service
public class FaqServiceImpl extends ServiceImpl<FaqMapper, Faq> implements FaqService {

    @Override
    public Long createFaq(FaqSaveDTO dto) {
        Faq faq = new Faq();
        // BeanUtils.copyProperties：同名字段自动拷贝，避免一个个 set
        BeanUtils.copyProperties(dto, faq);
        if (faq.getStatus() == null) {
            faq.setStatus(1);                 // 默认上架
        }
        if (faq.getSortNo() == null) {
            faq.setSortNo(0);
        }
        faq.setViewCount(0);
        this.save(faq);                        // INSERT，id 自动回填到 faq.id
        return faq.getId();
    }

    @Override
    public void updateFaq(Long id, FaqSaveDTO dto) {
        Faq exist = this.getById(id);
        if (exist == null) {
            throw new BusinessException(ContentErrorCode.FAQ_NOT_FOUND);
        }
        Faq faq = new Faq();
        BeanUtils.copyProperties(dto, faq);
        faq.setId(id);
        // updateById 只更新非 null 字段；带 @Version 会自动乐观锁
        this.updateById(faq);
    }

    @Override
    public void deleteFaq(Long id) {
        Faq exist = this.getById(id);
        if (exist == null) {
            throw new BusinessException(ContentErrorCode.FAQ_NOT_FOUND);
        }
        this.removeById(id);    // 因为有 @TableLogic，实际是 UPDATE deleted=1
    }

    @Override
    public FaqVO getFaqDetail(Long id) {
        Faq faq = this.getById(id);
        if (faq == null) {
            throw new BusinessException(ContentErrorCode.FAQ_NOT_FOUND);
        }
        // 浏览量 +1（演示一个简单的自增更新，生产环境高并发可用 SQL 原子自增）
        Faq update = new Faq();
        update.setId(id);
        update.setViewCount(faq.getViewCount() + 1);
        this.updateById(update);
        faq.setViewCount(faq.getViewCount() + 1);

        FaqVO vo = new FaqVO();
        BeanUtils.copyProperties(faq, vo);
        return vo;
    }

    @Override
    public PageResult<FaqVO> pageFaq(FaqQuery query) {
        // 1. 构造分页对象
        Page<Faq> page = new Page<>(query.getPageNum(), query.getPageSize());

        // 2. 构造动态查询条件（Lambda 方式，编译期可检查字段名，避免手写字符串出错）
        LambdaQueryWrapper<Faq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(query.getCategory() != null && !query.getCategory().isBlank(),
                        Faq::getCategory, query.getCategory())
               .eq(query.getStatus() != null, Faq::getStatus, query.getStatus())
               .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(Faq::getQuestionEn, query.getKeyword())
                              .or().like(Faq::getQuestionZh, query.getKeyword()))
               .orderByAsc(Faq::getSortNo)
               .orderByDesc(Faq::getId());

        // 3. 执行分页查询
        Page<Faq> result = this.page(page, wrapper);

        // 4. Entity 列表转 VO 列表
        List<FaqVO> voList = result.getRecords().stream().map(faq -> {
            FaqVO vo = new FaqVO();
            BeanUtils.copyProperties(faq, vo);
            return vo;
        }).toList();

        return PageResult.of(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }
}
```

**要点解释**
- `LambdaQueryWrapper`：链式拼 `WHERE`，每个条件第一个参数是「条件是否生效」，实现了动态 SQL——条件为 null 时自动跳过，不会拼出 `category=null`。
- `.and(w -> w.like(...).or().like(...))`：把「英文问题或中文问题模糊匹配」用括号包成一组，避免和其它条件 OR 优先级错乱。
- 生产中 Entity→VO 转换推荐 MapStruct（编译期生成，性能好），本卷用 Spring 自带 BeanUtils 降低门槛。

---

## 第 17 步：业务闭环⑤——Controller 层（处理 HTTP）

路径：`content/controller/FaqController.java`

```java
package com.mars.concert.content.controller;

import com.mars.concert.common.result.PageResult;
import com.mars.concert.common.result.Result;
import com.mars.concert.content.model.dto.FaqSaveDTO;
import com.mars.concert.content.model.query.FaqQuery;
import com.mars.concert.content.model.vo.FaqVO;
import com.mars.concert.content.service.FaqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * FAQ 接口（C 端 + 后台共用一套，学习期先不拆）。
 *
 * @RestController = @Controller + @ResponseBody：
 *   类被识别为控制器，且每个方法返回值自动转成 JSON（不再去找页面模板）。
 * @RequestMapping：类级别公共路径前缀。
 * @RequiredArgsConstructor：Lombok 为所有 final 字段生成构造函数，
 *   Spring 通过「构造器注入」把 FaqService 注入进来（推荐的注入方式，不可变、易测试）。
 */
@Tag(name = "内容-FAQ常见问题")
@RestController
@RequestMapping("/api/v1/faqs")
@RequiredArgsConstructor
public class FaqController {

    private final FaqService faqService;

    /** 新增：POST /api/v1/faqs，请求体是 JSON */
    @Operation(summary = "新增FAQ")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody FaqSaveDTO dto) {
        return Result.success(faqService.createFaq(dto));
    }

    /** 修改：PUT /api/v1/faqs/123 */
    @Operation(summary = "修改FAQ")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody FaqSaveDTO dto) {
        faqService.updateFaq(id, dto);
        return Result.success();
    }

    /** 删除：DELETE /api/v1/faqs/123 */
    @Operation(summary = "删除FAQ(逻辑删除)")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        faqService.deleteFaq(id);
        return Result.success();
    }

    /** 详情：GET /api/v1/faqs/123 */
    @Operation(summary = "FAQ详情(浏览量+1)")
    @GetMapping("/{id}")
    public Result<FaqVO> detail(@PathVariable Long id) {
        return Result.success(faqService.getFaqDetail(id));
    }

    /**
     * 分页列表：GET /api/v1/faqs?category=SHOW&pageNum=1&pageSize=10
     * FaqQuery 是普通 POJO，SpringMVC 会把同名 query 参数自动 set 进去（不需要 @RequestBody）。
     */
    @Operation(summary = "FAQ分页条件查询")
    @GetMapping
    public Result<PageResult<FaqVO>> page(FaqQuery query) {
        return Result.success(faqService.pageFaq(query));
    }
}
```

再加一个探活接口，验证框架通不通。路径：`common/... ` 下新建 `common/controller/PingController.java`（先建 `common/controller` 文件夹）：

```java
package com.mars.concert.common.controller;

import com.mars.concert.common.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PingController {

    // @Value 从 application.yml 读取配置，演示配置注入
    @Value("${spring.application.name}")
    private String appName;

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        return Result.success(Map.of(
                "app", appName,
                "time", System.currentTimeMillis()
        ));
    }
}
```

**HTTP 注解对照表（必须背熟）**
| 注解 | 作用 | 数据从哪来 |
|---|---|---|
| `@RestController` | 声明 REST 控制器，返回 JSON | — |
| `@RequestMapping("/api/v1/faqs")` | 类/方法路径前缀 | URL |
| `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping` | 限定 HTTP 方法 + 子路径 | URL |
| `@PathVariable` | 取路径里的 `/faqs/{id}` | URL 路径段 |
| `@RequestParam` | 取 `?category=SHOW`（可设 required/defaultValue） | Query 参数 |
| `@RequestBody` | 把请求体 JSON 反序列化为对象（POST/PUT） | 请求体 |
| `@Valid` | 触发 DTO 上的校验注解 | — |
| `@RequestHeader` | 取请求头（以后取 Authorization） | Header |

---

## 第 18 步：启动并用 Knife4j / Apifox 测试全部接口

### 18.1 启动
在 IDEA 打开 `MarsConcertApplication`，点 `main` 方法左侧绿色三角 → Run。
看到控制台最后出现：
```
Tomcat started on port 8080 (http)
Started MarsConcertApplication in x.xxx seconds
```
即成功。

### 18.2 浏览器验证探活
访问 http://localhost:8080/api/v1/ping
应返回：
```json
{"code":0,"message":"success","data":{"app":"mars-platform","time":1760...}}
```

### 18.3 Knife4j 在线调试
访问 http://localhost:8080/doc.html
- 能看到「内容-FAQ常见问题」分组和 5 个接口；
- 在「FAQ分页条件查询」里填 `category=SHOW`、`status=1`，点发送，能看到数据库里的真实数据；
- 在「新增FAQ」里用默认 JSON 发送，再查列表验证。

### 18.4 用 PowerShell/curl 快速验证新增与校验
新增（成功）：
```powershell
curl.exe -X POST http://localhost:8080/api/v1/faqs `
  -H "Content-Type: application/json" `
  -d '{\"category\":\"GENERAL\",\"questionEn\":\"Test?\",\"answer\":\"Yes\",\"status\":1}'
```
故意传空问题（触发校验，应返回 code=1001 和字段提示）：
```powershell
curl.exe -X POST http://localhost:8080/api/v1/faqs `
  -H "Content-Type: application/json" `
  -d '{\"category\":\"\",\"answer\":\"\"}'
```
查一个不存在的 id（应返回业务错误 6001，而不是 500）：
```
http://localhost:8080/api/v1/faqs/99999
```

---

## 19 步：把前端静态站点交给 Spring Boot 托管

**为什么要这一步？** 让页面和接口同源（都是 `localhost:8080`），开发联调没有跨域问题，将来 `java -jar` 部署一个进程就同时提供页面和 API。

### 19.1 拷贝前端
把整个 `mars_concert_site` 目录里的内容（`index.html`、`pages/`、`css/`、`js/`、`assets/`、`fonts/`、`favicon.svg`）**复制**到：
```
mars-platform/src/main/resources/static/
```
复制后形如 `static/index.html`、`static/pages/artist.html`、`static/css/...`。

> 用复制而不是移动：保留你原工程目录。Spring Boot 默认把 `classpath:/static/` 下文件映射到根路径，所以 `/pages/artist.html` 可直接访问。

### 19.2 重启后验证
- http://localhost:8080/ （自动找 index.html）→ 你的七幕宣传站；
- http://localhost:8080/pages/artist.html → 艺人页；
- http://localhost:8080/api/v1/faqs → 接口。
页面与接口同源，CORS 都可暂时不配（留着无害）。

---

## 第 20 步：前端封装 api.js 并改造 artist.html

### 20.1 新建 `src/main/resources/static/js/api.js`

```javascript
/* 统一的后端请求封装：自动拼前缀、带 token、解包 Result、统一错误提示 */
(function () {
  const BASE = '/api/v1';

  /**
   * 核心请求方法
   * @param {string} method  GET/POST/PUT/DELETE
   * @param {string} path    如 /faqs
   * @param {object} options { query, body }
   */
  async function request(method, path, options = {}) {
    const { query, body } = options;
    let url = BASE + path;
    if (query) {
      const qs = new URLSearchParams(
        Object.entries(query).filter(([, v]) => v !== undefined && v !== null && v !== '')
      ).toString();
      if (qs) url += '?' + qs;
    }

    const headers = { 'Accept': 'application/json' };
    const token = localStorage.getItem('mars_token');
    if (token) headers['Authorization'] = 'Bearer ' + token;   // 为下一卷 JWT 预留
    if (body) headers['Content-Type'] = 'application/json';

    const resp = await fetch(url, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined
    });

    let json;
    try {
      json = await resp.json();
    } catch (e) {
      throw new Error('服务器响应不是合法 JSON（HTTP ' + resp.status + '）');
    }

    if (json.code !== 0) {
      // 以后接入登录后，401 可在此统一跳登录页
      throw new Error(json.message || '请求失败');
    }
    return json.data;   // 成功时直接把 data 返回，业务代码不用再解包
  }

  window.MarsApi = {
    get:  (path, query) => request('GET', path, { query }),
    post: (path, body)  => request('POST', path, { body }),
    put:  (path, body)  => request('PUT', path, { body }),
    del:  (path)        => request('DELETE', path)
  };
})();
```

### 20.2 在 artist.html 引入 api.js，并让 FAQ 动态渲染

打开 `src/main/resources/static/pages/artist.html`（**注意：改的是复制进 static 的这份**）：

1）在 `</body>` 前，于 `functional.js` 之前加入：
```html
  <script src="../js/api.js?v=1"></script>
  <script src="../js/functional.js?v=4" defer></script>
```

2）把原来写死的 6 个 `.faq-item` 所在容器 `<div class="faq-list">…</div>` 整体替换为一个空容器：
```html
        <!-- FAQ：改为后端动态渲染 -->
        <div class="faq-list" id="faqList">
          <div class="faq-loading">Loading questions…</div>
        </div>
```

3）在 `static/js/` 下新建 `faq-loader.js`，负责拉数据并复用你已有的手风琴样式：
```javascript
/* 从后端加载 FAQ 并渲染（复用 functional.css 的 .faq-item 样式与手风琴交互） */
(function () {
  const listEl = document.getElementById('faqList');
  if (!listEl) return;

  function esc(s) {
    // 简单转义，防止 XSS（answer 来自后台，仍按不可信文本处理）
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function render(faqs) {
    if (!faqs.length) {
      listEl.innerHTML = '<div class="faq-loading">No questions yet.</div>';
      return;
    }
    listEl.innerHTML = faqs.map(f => `
      <div class="faq-item">
        <button class="faq-question">
          ${esc(f.questionEn)}
          <span class="faq-question__icon"></span>
        </button>
        <div class="faq-answer">
          <div class="faq-answer__inner">${esc(f.answer)}</div>
        </div>
      </div>
    `).join('');

    // 绑定手风琴：与 functional.js 中相同的同步测量逻辑
    const items = listEl.querySelectorAll('.faq-item');
    items.forEach(item => {
      const q = item.querySelector('.faq-question');
      const a = item.querySelector('.faq-answer');
      q.addEventListener('click', () => {
        const isOpen = item.classList.contains('is-open');
        items.forEach(other => {
          other.classList.remove('is-open');
          other.querySelector('.faq-answer').style.maxHeight = '0px';
        });
        if (!isOpen) {
          item.classList.add('is-open');
          a.style.maxHeight = 'none';
          const inner = a.querySelector('.faq-answer__inner');
          const h = inner ? inner.offsetHeight : a.scrollHeight;
          a.style.maxHeight = (h || 200) + 'px';
        }
      });
    });
  }

  async function load() {
    try {
      const data = await MarsApi.get('/faqs', { status: 1, pageNum: 1, pageSize: 50 });
      render(data.list || []);
    } catch (e) {
      listEl.innerHTML = '<div class="faq-loading">Failed to load: ' + esc(e.message) + '</div>';
    }
  }

  load();
})();
```

4）在 artist.html 中 `api.js` 之后引入：
```html
  <script src="../js/faq-loader.js?v=1" defer></script>
```

> **为什么手风琴逻辑不直接复用 functional.js？** functional.js 是在 DOMReady 时给「页面上已有的 .faq-item」绑定事件；而动态渲染发生在异步请求之后，那时初始绑定已结束，所以加载器渲染完要重新绑定。这也是「动态内容需要事件委托或重新绑定」的典型知识点。将来你可以把它优化成「在 `#faqList` 上做一次事件委托」，动态元素就无需重复绑定。

---

## 第 21 步：端到端联调与常见报错排查表

### 21.1 完整联调路径
1. 启动后端（8080）；
2. 浏览器打开 http://localhost:8080/pages/artist.html ；
3. FAQ 区域应显示数据库中的 6 条（英文问题），点击可展开，内容来自 MySQL；
4. 在 Knife4j 新增一条、刷新页面，新问题出现；删除一条，页面消失（数据库 `deleted=1` 仍在）。

至此你已打通：**浏览器 → fetch → DispatcherServlet → Controller → Service → MyBatis-Plus → MySQL → Result JSON → 前端渲染**。

### 21.2 常见报错对照表

| 现象/报错 | 原因 | 解决 |
|---|---|---|
| `Communications link failure` / `Connection refused` | MySQL 没启动或端口/密码错 | 启动 MySQL；核对 url/username/password |
| `Access denied for user 'root'` | 密码错 | 改 application.yml 密码 |
| `Unknown database 'mars_concert'` | 没建库 | 执行第 3 步建库脚本 |
| `Table 'mars_concert.ct_faq' doesn't exist` | 没建表 | 执行建表 SQL |
| `Invalid bound statement (not found)` / Mapper 找不到 | 没被扫描 | 启动类加 `@MapperScan("com.mars.concert.**.mapper")`，或接口上加 `@Mapper` |
| 分页查全表、没生效 | 没装分页插件 | 配置 MybatisPlusConfig 的 PaginationInnerInterceptor |
| `@NotBlank` 不生效 | 没加 @Valid 或缺 validation 依赖 | Controller 参数加 `@Valid`；pom 加 starter-validation |
| 时间差 8 小时 | 时区 | url 带 serverTimezone，jackson 配 time-zone |
| 中文/表情乱码或存不进 emoji | 字符集 | 库表用 utf8mb4，url 带 characterEncoding=utf8 |
| 前端跨域 CORS 报错 | 前后端不同源未放行 | 用第 19 步同源，或保留 WebMvcConfig CORS |
| 404 静态页面 | 没放 static 或路径错 | 确认在 `resources/static/` 下，路径区分大小写 |
| Lombok 注解爆红/方法找不到 | 没装插件/没开注解处理 | IDEA 装 Lombok 插件 + 开启 Annotation Processing |
| 端口 8080 被占用 | 其它进程占用 | yml 改 port，或 `netstat -ano | findstr :8080` 查进程 |
| `jakarta.servlet` 找不到类 | Boot3 用错 Boot2 的包/依赖 | 校验/MyBatis/Knife4j 都用 jakarta / boot3 专用版本 |
| 删除接口却把数据真删了 | 没加 @TableLogic | 实体字段加 `@TableLogic` |
| 接口文档打不开 | 依赖版本不匹配 | knife4j 用 4.5.0（openapi3-jakarta） |

### 21.3 自检 Checklist（全部能勾上，本卷过关）
- [ ] `mvn -v`、`java -version` 都是 17/3.9.x
- [ ] 启动无报错，`/api/v1/ping` 返回 code=0
- [ ] `/doc.html` 看到 5 个 FAQ 接口
- [ ] 列表/详情/新增/修改/删除（逻辑删）全部在 Knife4j 调通
- [ ] 空 questionEn 提交返回 1001 与字段提示
- [ ] 查不存在 id 返回 6001 业务错误，非 500
- [ ] 列表接口控制台能打印出带 LIMIT 的 SQL
- [ ] `artist.html` 的 FAQ 来自数据库，可展开，新增数据后刷新可见
- [ ] 删除一条后数据库该行 `deleted=1` 且列表不再出现

---

## 本卷小结与下一卷预告

本卷你已经掌握并亲手落地：
- 手动 Maven + Spring Boot 3 工程、依赖作用、yml 配置；
- 三层架构（Controller/Service/Mapper）与 DTO/Entity/VO 模型分层；
- 统一返回、错误码、业务异常、全局异常处理、参数校验、跨域、接口文档；
- MyBatis-Plus：BaseMapper/IService、LambdaQueryWrapper 动态条件、分页、逻辑删除、乐观锁、自动填充；
- 一个完整业务 CRUD 闭环 + 现有前端动态化对接。

**第 2 卷预告：会员认证与 Spring Security**
- 会员表/观演人表建表；注册、短信验证码登录（验证码先打印控制台）、密码登录；
- Spring Security 6 过滤器链 + JWT 双 Token（access/refresh）+ Redis 登录态；
- `@CurrentUser` 参数解析器、RBAC 角色权限、`@PreAuthorize`；
- `api.js` 自动带 token 与 401 无感刷新；一个需要登录才能访问的「我的收藏/我的订单（占位）」接口。

需要我继续输出第 2 卷（含 Redis 安装、Spring Security 6 全套配置、JWT 工具类的逐文件代码）时告诉我即可。

---

# 附录 A：IntelliJ IDEA 专属操作指引（全程图形界面，可替代手册中的命令行）

> 适用 IDEA 2023.x ~ 2026.x。**推荐 IntelliJ IDEA Ultimate**（在校学生/教师可用教育邮箱在 JetBrains 官网免费申请正版）：它自带 Database 数据库工具、HTTP Client、完整 Spring 支持。
> 若用 **Community 社区版**：跑 Spring Boot 完全没问题，但没有内置 Database 工具，第 3 步建库可用 Navicat / DBeaver / MySQL 命令行代替，其余步骤一致。

## A.1 用 IDEA 内置 Maven，无需单独安装 Maven

IDEA 自带一个 Maven，第 2 步可跳过手工安装。但**镜像仍要配置**，否则下载慢：

1. `File → Settings → Build, Execution, Deployment → Build Tools → Maven`
2. 三项关键设置：
   - **Maven home path**：选择 `Bundled (Maven 3)`（用 IDEA 自带的）；如果你已按手册装了独立 Maven，也可选它的目录。
   - **User settings file**：勾选右侧 `Override`，点输入框旁的「文件夹/创建」图标，在 `C:\Users\23081\.m2\settings.xml` 新建一个 settings.xml，内容填入第 2.3 节的阿里云镜像配置。
   - **Local repository**：默认 `C:\Users\23081\.m2\repository` 即可，想改 D 盘就勾 Override 选目录。
3. 点 OK。以后所有 Maven 工程共用这份镜像配置。

> 查看依赖是否在下：IDEA 底部状态栏有进度条；右侧边栏 `Maven`（大象图标）面板可管理。

## A.2 在 IDEA 里创建工程（替代第 4 步的命令行建目录）

**方式一（推荐，符合你"手动创建 Maven 工程"）：**
1. 先在资源管理器 `C:\Users\23081\Desktop\Mars_Concert\` 下新建空文件夹 `mars-platform`。
2. IDEA：`File → Open`，选中 `mars-platform` 文件夹 → `OK` → 选 `Open in current window`（可勾不再询问）。
3. 在工程根目录右键 `New → File`，输入 `pom.xml`，粘贴第 4.2 节内容并按 `Ctrl+Shift+O`（或右上角浮层 `Load Maven Changes`）。
4. 再右键 `src` 等目录 New Directory 时，IDEA 一旦识别出 Maven 结构，会提供 `src/main/java`、`src/main/resources` 这种**带特殊含义的目录快捷项**，直接选即可。

**方式二（用向导生成骨架，再改 pom）：**
`File → New → Project`，左侧选 `Maven Archetype`（或 Maven），JDK 选 17，填 groupId=`com.mars`、artifactId=`mars-platform`，Create。生成后用第 4.2 节 pom 覆盖。

> 本手册后续都以"手动方式"结构为准。

## A.3 关键：配置 JDK 17 与源码目录

打开工程后第一件事：
1. `File → Project Structure`（快捷键 `Ctrl+Alt+Shift+S`）：
   - `Project → SDK`：选 17（下拉没有就 `Add SDK → Download JDK`，选 Eclipse Temurin 版本 17，IDEA 自动下载）。
   - `Project → Language level`：选 `17`。
2. 同一窗口左侧 `Modules → Sources`：确认
   - `src/main/java` 文件夹是**蓝色**（标记为 Sources Root）；
   - `src/main/resources` 文件夹上有**资源条纹理**（标记为 Resources Root）。
   - 如果没识别上：选中目录 → 上方点 `Sources` / `Resources` 按钮（或右键目录 `Mark Directory as`）。
3. `File → Settings → Build, Execution, Deployment → Compiler → Java Compiler`：
   - `Project bytecode version` 设为 `17`；下方模块 Target bytecode 也为 17。

> 常见坑：pom 改了之后 Language level 总被重置回 8/11。本手册 pom 已设 `<java.version>17</java.version>`，Reload Maven 后会自动固定为 17。

## A.4 开启 Lombok 与注解处理（否则 @Data 一片红）

1. 新版 IDEA 已内置 Lombok 插件：`Settings → Plugins → Installed` 搜索 Lombok 确认启用（没有就 Marketplace 搜 Lombok 安装并重启）。
2. `Settings → Build, Execution, Deployment → Compiler → Annotation Processors`：
   - 勾选 **Enable annotation processing**（对默认 profile 勾上即可）。
3. 重新 `Build → Rebuild Project`，`@Data` 产生的 getter/setter 即可用（调用处不再报红）。

## A.5 在 IDEA 里建包、建类（最省事的姿势）

- 在蓝色的 `src/main/java` 上右键 `New → Package`，**一次性输入完整包名**：
  `com.mars.concert.content.service.impl`
  IDEA 会自动创建全部嵌套文件夹（不要一层一层建）。
- 在包上右键 `New → Java Class`，输入类名。按手册路径逐个创建即可。
- 创建接口：`New → Java Class` 弹窗里把 Kind 选为 `Interface`，或直接输入名后在文件里写 `interface`。
- 包名左侧的小齿轮 `⚙ → Compact Middle Packages`：取消勾选可看到每层文件夹；勾选则折叠显示，按你喜好。

## A.6 用 IDEA Database 工具建库建表（替代第 3 步命令行，仅 Ultimate）

1. 右侧边栏点 `Database`（没有就 `View → Tool Windows → Database`）。
2. 点 `+ → Data Source → MySQL`。
3. 填写：Host=`localhost`、Port=`3306`、User=`root`、Password=`root123`。
4. 首次使用点 Driver 行的提示 `Download Driver Files`（IDEA 自动下载 MySQL 驱动），完成后点 `Test Connection`，出现绿色 `Successful` 即连通。
5. 先不选 Database，直接 `OK`。然后在该数据源上右键 `New → Query Console`（或点 `@` 图标打开控制台）。
6. 粘贴第 3.2 节整段 SQL（含 `CREATE DATABASE` 与 `INSERT`），全选后按 `Ctrl+Enter` 执行。
7. 左侧数据源上右键 `Refresh`，展开就能看到 `mars_concert → tables → ct_faq`，双击表名可直接查看 6 行数据。

> 以后写代码时，在 Entity 字段上 IDEA 还能根据真实表给提示；JPQ/SQL 控制台也能直接跑语句。

## A.7 编写 application.yml 获得自动提示（可选增强）

为让 yml 有 Spring 配置自动补全，在 pom.xml 的 `<dependencies>` 内加：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```
Reload Maven 后，`application.yml` 输入 `server.` 会有下拉提示。

## A.8 在 IDEA 中启动与热重启

1. 打开 `MarsConcertApplication.java`，点 `main` 方法左侧绿色三角 → `Run 'MarsConcertApplication'`。
2. 启动后该运行配置常驻右上角，绿色三角运行、虫子图标 Debug（可打断点）。
3. 浏览器/下方控制台看启动日志；想停止点红色方块。
4. 改了 Java 代码想快速生效：
   - **Debug 模式**下，改完方法体按 `Ctrl+F9`（Build Project），IDEA 的 HotSwap 会尝试热替换（仅方法体内修改可直接生效）。
   - 推荐开发时安装 Spring Boot DevTools（可选）：加依赖后，classpath 文件变化会自动重启（比手动重启快）：
     ```xml
     <dependency>
         <groupId>org.springframework.boot</groupId>
         <artifactId>spring-boot-devtools</artifactId>
         <scope>runtime</scope>
         <optional>true</optional>
     </dependency>
     ```
   - IDEA 里还需 `Settings → Advanced Settings → 勾选 Allow auto-make to start even if developed application is currently running`，并在 `Compiler` 里勾 `Build project automatically`。

## A.9 用 IDEA HTTP Client 测接口（替代第 18.4 节 curl，强烈推荐）

在工程根目录新建文件夹 `http`，里面新建文件 `faq.http`，粘贴：

```http
### 探活
GET http://localhost:8080/api/v1/ping

### 分页查询(只看上架的演出类)
GET http://localhost:8080/api/v1/faqs?category=SHOW&status=1&pageNum=1&pageSize=10

### 详情
GET http://localhost:8080/api/v1/faqs/1

### 新增
POST http://localhost:8080/api/v1/faqs
Content-Type: application/json

{
  "category": "GENERAL",
  "questionEn": "How do I get to the venue?",
  "questionZh": "怎么去场馆？",
  "answer": "Nanjing Tangshan Music Valley. Shuttle buses run from nearby metro stations on show days.",
  "sortNo": 7,
  "status": 1
}

### 故意触发参数校验(空问题) —— 应返回 code 1001
POST http://localhost:8080/api/v1/faqs
Content-Type: application/json

{
  "category": "",
  "answer": ""
}

### 修改(把 id 改成你库里真实存在的)
PUT http://localhost:8080/api/v1/faqs/7
Content-Type: application/json

{
  "category": "ENTRY",
  "questionEn": "How do I get to the venue?",
  "answer": "Updated answer: take the official shuttle.",
  "sortNo": 7,
  "status": 1
}

### 删除(逻辑删除)
DELETE http://localhost:8080/api/v1/faqs/7
```

用法：每个 `###` 分隔一个请求，请求左侧会出现绿色三角，点击即发；右侧弹出结果面板展示 JSON。
- 可在请求间用变量，例如顶部写 `@host = http://localhost:8080`，后面用 `{{host}}/api/v1/faqs`，切环境只改一处。
- 下一卷做登录后，登录接口返回的 token 可自动提取到变量，后续请求自动带 `Authorization` 头。

## A.10 常用排错操作（IDEA 场景）

| 现象 | 在 IDEA 里怎么处理 |
|---|---|
| pom 依赖飘红、下载一半失败 | 右侧 Maven 面板点刷新（Reload All Maven Projects）；还不行则 `mvn` 面板 `Lifecycle → clean` 后重新 Reload；确认 A.1 镜像已配 |
| 启动类没有绿色三角 | `src/main/java` 未被标记为 Sources Root，按 A.3 标记；或工程未被识别为 Maven（右键 pom.xml `Add as Maven Project`） |
| `package java.sql does not exist` / jakarta 报错 | JDK 没选 17，按 A.3 检查 Project SDK 与 bytecode |
| 配置文件不生效/找不到 | 确认 `application.yml` 在被标记为 Resources Root 的 `resources` 目录下，编译后应出现在 `target/classes` |
| 改了 yml 没反应 | 重启应用（yml 不会热加载，DevTools 也会触发重启生效） |
| 端口占用启动失败 | 控制台点红色方块停旧进程；或 yml 改 `server.port` |
| 想查看编译后结构 | 左侧 Project 面板顶部切到 `Project` 视图（非 Android/Packages 视图）可见 target |
| 找不到某自动配置/想排查 Bean | 启动配置 VM options 加 `--debug`，控制台会打印自动装配报告（Positive/Negative matches） |
| SQL 打印太多 | 学习期保留；想关去掉 application.yml 的 `log-impl` 或把 `com.mars.concert` 日志级别改 info |

## A.11 建议的日常工作区布局

- 左：Project（项目结构）
- 右：Maven（依赖/生命周期）、Database（跑 SQL/看数据）
- 底部：Terminal（可选跑 mvn）、Run（启动日志/接口日志，能看到 MyBatis 打印的 SQL）、HTTP Client 结果
- 调试：在 Controller/Service 行号右侧点一下打红点，Debug 启动，请求到达后可逐行看「HTTP 参数 → DTO → Entity → SQL 结果」的真实数据流动，这是理解全链路最快的方法。
