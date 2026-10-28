package com.mars.ai.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.mars.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Map;
import java.util.Properties;

/**
 * AI 服务不引 Spring Cloud，直接用 nacos-client 手工注册/注销。
 * 注册名 mars-ai-service，分组 MARS_GROUP，端口 9009。
 * 注册失败只告警，不影响服务启动（本地开发 Nacos 未启动时仍可用直连端口调试）。
 */
@Component
public class NacosRegistration implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistration.class);

    private final AiProperties properties;

    private NamingService namingService;
    private Instance instance;

    public NacosRegistration(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AiProperties.Nacos nacos = properties.getNacos();
        if (!nacos.isEnabled()) return;
        try {
            Properties props = new Properties();
            props.put(PropertyKeyConst.SERVER_ADDR, nacos.getServerAddr());
            namingService = NacosFactory.createNamingService(props);

            Instance target = new Instance();
            target.setIp(InetAddress.getLocalHost().getHostAddress());
            target.setPort(nacos.getPort());
            target.setServiceName(nacos.getServiceName());
            target.setClusterName("DEFAULT");
            target.setWeight(1.0D);
            target.setEphemeral(true);
            target.setMetadata(Map.of("version", "1.0.0"));

            namingService.registerInstance(nacos.getServiceName(), nacos.getGroup(), target);
            this.instance = target;
            log.info("已注册到 Nacos：{}@{}:{} group={}",
                    nacos.getServiceName(), target.getIp(), target.getPort(), nacos.getGroup());
        } catch (Exception e) {
            log.warn("Nacos 注册失败（不影响启动）：{}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        AiProperties.Nacos nacos = properties.getNacos();
        if (namingService == null || instance == null) return;
        try {
            namingService.deregisterInstance(nacos.getServiceName(), nacos.getGroup(), instance);
            log.info("已从 Nacos 注销：{}", nacos.getServiceName());
        } catch (Exception e) {
            log.warn("Nacos 注销失败：{}", e.getMessage());
        } finally {
            try {
                namingService.shutDown();
            } catch (Exception ignored) {
                // 关闭连接失败无需处理
            }
        }
    }
}
