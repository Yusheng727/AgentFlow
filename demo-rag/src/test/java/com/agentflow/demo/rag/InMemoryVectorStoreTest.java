package com.agentflow.demo.rag;

import com.agentflow.demo.rag.InMemoryVectorStore.Result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U8：{@link InMemoryVectorStore} 测试——top-k 相似度排序、确定性（同 query 同结果）、
 * 空库 / k>库大小 / 空 query 等边界。
 */
class InMemoryVectorStoreTest {

    @Test
    @DisplayName("top-k 按相似度降序返回重叠文档，同 query 同结果（确定性）")
    void topKOrdersBySimilarityDeterministically() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        // 词间空格分隔（tokenSet 按空白切分，零依赖分词），保证可计算的重叠
        store.add("串行 DAG 编排 引擎");        // 与 query「串行 编排 引擎」重叠 3 → 最高
        store.add("HITL 人工 审批 暂停 恢复");   // 重叠 0 → 被过滤
        store.add("并行 工作流 编排 引擎");      // 重叠 2（编排/引擎）

        List<Result> hits = store.topK("串行 编排 引擎", 3);

        // score>0 才命中（零重叠文档被过滤），且降序
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(r -> r.doc().text())
                .containsExactly("串行 DAG 编排 引擎", "并行 工作流 编排 引擎");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
        // 确定性：再次调用同 query → 同顺序
        List<Result> again = store.topK("串行 编排 引擎", 3);
        assertThat(again).extracting(r -> r.doc().text())
                .containsExactlyElementsOf(hits.stream().map(r -> r.doc().text()).toList());
    }

    @Test
    @DisplayName("空库 → 空列表；空 query → 空列表")
    void emptyStoreOrQueryReturnsEmpty() {
        InMemoryVectorStore empty = new InMemoryVectorStore();
        assertThat(empty.topK("anything", 3)).isEmpty();

        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add("some doc content here");
        assertThat(store.topK("", 3)).isEmpty();
        assertThat(store.topK(null, 3)).isEmpty();
    }

    @Test
    @DisplayName("k 大于命中数 → 返回全部重叠文档；k=0 → 空")
    void kLargerThanMatchesOrZero() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add("alpha 检索 结果");
        store.add("beta 检索");

        // k=99 超界 → 返回全部重叠（2 篇都含「检索」）
        assertThat(store.topK("检索 结果 alpha", 99)).hasSize(2);
        assertThat(store.topK("检索 结果 alpha", 0)).isEmpty();
    }

    @Test
    @DisplayName("无重叠 query → 全相似度 0，过滤空结果")
    void noOverlapReturnsEmpty() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add("完全中文文档内容");
        // query 无任何 token 重叠 → 全部 score 0 → 无命中
        assertThat(store.topK("zzzzzzz qqqqq", 5)).isEmpty();
    }
}
