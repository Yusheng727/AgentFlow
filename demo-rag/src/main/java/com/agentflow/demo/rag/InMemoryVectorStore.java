package com.agentflow.demo.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内存向量库（U8 RAG demo）：doc 列表 + <b>确定性</b> token 集合 embedder。
 *
 * <p>离线可测（无外部向量服务，保证 {@code mvn verify} 绿）：
 * <ul>
 *   <li>{@link #embed}：按空格切 token → 小写 → 词袋向量（token 是否出现），纯确定性</li>
 *   <li>{@link #topK}：query 词袋与 doc 词袋的<b>余弦相似度</b>排序取 top-k（{@link Result#score}）</li>
 *   <li>同 query 同结果（确定性）；空库/空 query 防御</li>
 * </ul>
 */
public class InMemoryVectorStore {

    private final List<Doc> docs = new ArrayList<>();
    private long nextId;

    /** 文档记录：id + 原文 + 预计算词袋（embedder 确定性）。 */
    public record Doc(long id, String text, Set<String> tokens) {
    }

    /** 检索结果：命中 doc + 与 query 的余弦相似度（0 无重叠 ~ 1 全重叠）。 */
    public record Result(Doc doc, double score) {
    }

    /** 加入一篇文档（原文 → 预计算词袋）。返回 doc id。 */
    public long add(String text) {
        if (text == null || text.isBlank()) {
            return -1L;
        }
        Doc d = new Doc(nextId++, text, tokenSet(text));
        docs.add(d);
        return d.id();
    }

    /**
     * top-k 检索：query 词袋与各 doc 词袋的余弦相似度降序，取前 k。
     * 空库/空 query → 空列表；k 大于库大小 → 全量（降序）。
     */
    public List<Result> topK(String query, int k) {
        if (docs.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> qTokens = tokenSet(query);
        int effectiveK = Math.max(0, k);
        return docs.stream()
                .map(d -> new Result(d, cosine(qTokens, d.tokens())))
                .filter(r -> r.score() > 0.0)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(effectiveK)
                .collect(Collectors.toList());
    }

    /** 库大小（测试断言用）。 */
    public int size() {
        return docs.size();
    }

    // ──────────────────────────── embedder ────────────────────────────

    /** 确定性词袋：按空白切 token + 小写 + 去空白。 */
    private static Set<String> tokenSet(String text) {
        Set<String> tokens = new HashSet<>();
        for (String t : text.toLowerCase().split("[\\s,.;:!?()]+")) {
            if (!t.isBlank()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** 余弦相似度：|A∩B| / sqrt(|A|·|B|)。任一词袋空 → 0。 */
    private static double cosine(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        long inter = a.stream().filter(b::contains).count();
        return inter / Math.sqrt((double) a.size() * b.size());
    }
}