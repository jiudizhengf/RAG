package org.example.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rag.repository.KbDocumentRepository;
import org.springframework.ai.embedding.EmbeddingModel;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量检索服务
 * 职责说明：
 * 1. 将文本转换为向量（向量化）
 * 2. 在向量数据库中检索相似内容
 * 3. 处理权限过滤
 * 核心概念：
 * - Embedding（嵌入）：将文本转换为数值向量，语义相似的文本向量距离更近
 * - 余弦相似度：衡量两个向量相似度的指标，范围[-1, 1]，越接近1越相似
 * - 向量检索：通过计算向量相似度来查找最相关的内容
 * PostgreSQL向量操作符：
 * - <->: 欧几里得距离（L2距离），越小越相似
 * - <=>: 余弦距离，越小越相似
 * - <#>: 负内积，越大越相似
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {
    private final EmbeddingModel embeddingModel;
    private final JdbcClient jdbcClient;
    private final KbDocumentRepository kbDocumentRepository;
    private static final int TOP_K = 5; // 默认返回最相似的5条记录

    public List<String> search(String query,List<String> userRoles) {
        log.info("开始向量检索，query={}, userRoles={}", query, userRoles);

        // 1. 将查询文本转换为向量

        List<Double> queryEmbedding = embeddingModel.embed(query);
        log.debug("查询向量维度：{}", queryEmbedding.size());

        // 2. 构建向量检索SQL
        // 核心逻辑：
        // - JOIN kb_documents: 关联文档表获取权限信息
        // - WHERE permission_group IN: 只查询用户有权访问的文档
        // - ORDER BY embedding <->: 按向量距离排序（越近越相似）
        // - LIMIT 3: 只返回最相关的3个结果
        String searchSql = """
              SELECT dc.content
              FROM document_chunks dc
              JOIN kb_documents kd ON dc.doc_id = kd.id
              WHERE kd.permission_group IN (:userRoles)
              ORDER BY dc.embedding <-> :embedding::vector
              LIMIT :topK
          """;
        // 3. 执行查询
        // PostgreSQL的pgvector扩展会计算向量距离，返回最相似的结果
        List<String> relatedContexts = jdbcClient.sql(searchSql)
                .param("embedding", queryEmbedding.toString())  // 向量转为字符串格式
                .param("userRoles", userRoles)                  // 权限过滤
                .param("topK", TOP_K)                           // 返回TOP-K
                .query(String.class)
                .list();

        log.info("向量检索完成，找到{}个相关片段", relatedContexts.size());

        return relatedContexts;
    }
    /**
     * 为文本生成向量（用于存储）
     *
     * @param text 文本内容
     * @return 向量数组
     * 使用场景：
     * - 上传文档时，为文档片段生成向量
     * - 为问题生成向量
     */
    public List<Double> embed(String text) {
        log.debug("生成向量，text长度={}", text.length());
        return embeddingModel.embed(text);
    }
}
