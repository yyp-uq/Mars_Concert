package com.mars.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * mars-common 自带的 Mapper 自我注册（操作日志表）。
 * 各业务服务启动类上的 @MapperScan 只扫自己域的 mapper 包，
 * 本配置以独立的 @MapperScan 叠加注册，互不影响。
 */
@Configuration
@MapperScan("com.mars.common.aspect")
public class CommonMapperScanConfig {
}
