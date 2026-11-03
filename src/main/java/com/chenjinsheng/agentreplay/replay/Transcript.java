package com.chenjinsheng.agentreplay.replay;

import com.chenjinsheng.agentreplay.agent.model.LlmMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 给完整消息序列取摘要。
 *
 * <p>为什么要摘要而不是直接比字符串：消息序列里可能有几万字的文件内容，
 * 每次重放都存一份、比一遍不现实。摘要把「全部内容」压成 64 个字符，
 * 比较成本恒定，而且能存进 {@code agent_run} 这张表里。</p>
 *
 * <p>用 SHA-256 而不是 {@link String#hashCode()}：后者的输出空间只有 32 位，
 * 两条不同的消息序列撞上同一个值的概率并不低，一旦撞了，重放校验就会<b>假通过</b>——
 * 这正是最不能出的错。</p>
 */
public final class Transcript {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Transcript() {
    }

    public static String hash(List<LlmMessage> messages) {
        try {
            // record 的序列化顺序由字段声明顺序决定，Map 参数用 LinkedHashMap 保存插入顺序，
            // 所以同样的消息序列每次都会得到同样的字节流
            byte[] json = MAPPER.writeValueAsBytes(messages);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", e);
        } catch (Exception e) {
            throw new IllegalStateException("消息序列序列化失败", e);
        }
    }

    /** 给日志用的短摘要。 */
    public static String shortHash(String hash) {
        if (hash == null) {
            return "(无)";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
