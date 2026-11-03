package com.chenjinsheng.agentreplay.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 全局配置，前缀 {@code agent.*}。
 *
 * <p>这里只有一个字段值得解释：{@link Llm#getMode()}。它决定这次运行是
 * 「真的去请求模型」还是「只读录制好的响应」。整个项目的确定性都挂在这个开关上。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Llm llm = new Llm();
    private Files files = new Files();
    private Loop loop = new Loop();

    @Getter
    @Setter
    public static class Llm {

        /** {@code real} 或 {@code recorded}。 */
        private String mode = "recorded";

        private String baseUrl = "https://api.deepseek.com";

        private String model = "deepseek-chat";

        /** 只从环境变量注入，不落盘。 */
        private String apiKey = "";

        private Duration timeout = Duration.ofSeconds(60);

        private double temperature = 0;

        public boolean isReal() {
            return "real".equalsIgnoreCase(mode);
        }
    }

    @Getter
    @Setter
    public static class Files {

        /** 工具可读写的根目录；file_write 会做路径穿越检查。 */
        private Path workspace = Path.of("./workspace");
    }

    @Getter
    @Setter
    public static class Loop {

        /** 一次运行最多走多少步，防止模型反复调工具停不下来。 */
        private int maxSteps = 8;
    }
}
