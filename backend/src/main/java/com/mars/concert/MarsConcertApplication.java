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
@org.springframework.scheduling.annotation.EnableScheduling   // 第4卷：兜底关单/座位对账定时任务
public class MarsConcertApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarsConcertApplication.class, args);
    }
}
