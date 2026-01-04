package org.example.rag.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.example.rag.entity.KbDocument;
import org.example.rag.service.StorageService;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.data.redis.core.RedisTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RabbitConfig;
import org.example.rag.entity.dto.DocUploadMessage;
import org.example.rag.repository.KbDocumentRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG文档上传消息消费者（自动ACK版本）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagConsumer {

    private final KbDocumentRepository documentRepository;
    private final EmbeddingModel embeddingModel;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final StorageService storageService;

    @RabbitListener(queues = RabbitConfig.RAG_UPLOAD_QUEUE, concurrency = "5-10")
    public void processUpload(DocUploadMessage msg) {
        String distinctKey = "rag:process:" + msg.getDocId();

        try {
            log.info("========================================");
            log.info("收到MQ消息，开始处理文件上传");
            log.info("文档ID: {}", msg.getDocId());
            log.info("========================================");

            // 1. 幂等性检查
            if (!checkIdempotent(distinctKey, msg.getDocId())) {
                log.warn("检测到重复处理，跳过，docId={}", msg.getDocId());
                return;  // Spring自动ACK
            }

            // 2. 检查文档是否存在
            KbDocument kbDoc = documentRepository.findById(msg.getDocId())
                    .orElseThrow(() -> {
                        log.error("文档不存在，docId={}", msg.getDocId());
                        redisTemplate.delete(distinctKey);
                        return new IllegalArgumentException("文档不存在");
                    });

            // 3. 更新状态为处理中
            log.info("更新文档状态为PROCESSING，docId={}", msg.getDocId());
            kbDoc.setStatus("PROCESSING");
            documentRepository.save(kbDoc);

            // 4. 处理文档
            log.info("开始处理文档，docId={}", msg.getDocId());
            processDocument(kbDoc, msg);

            // 5. 完成
            log.info("文档处理完成，docId={}, 状态={}", msg.getDocId(), kbDoc.getStatus());

        } catch (Exception e) {
            log.error("文档处理失败，docId={}", msg.getDocId(), e);
            handleFailure(msg.getDocId(), e);
            // 抛出异常，Spring自动NACK并重试
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        } finally {
            redisTemplate.delete(distinctKey);
        }
    }

    /**
     * 幂等性检查
     */
    private boolean checkIdempotent(String distinctKey, Long docId) {
        Boolean isAbsent = redisTemplate.opsForValue()
                .setIfAbsent(distinctKey, "PROCESSING", Duration.ofHours(1));

        if (Boolean.FALSE.equals(isAbsent)) {
            log.warn("幂等性检查失败，正在处理或已处理，docId={}", docId);
            return false;
        }

        return true;
    }

    /**
     * 处理文档的核心逻辑
     */
    private void processDocument(KbDocument kbDoc, DocUploadMessage msg) throws Exception {
        String ossKey = msg.getLocalFilePath();

        // 1. 从OSS下载并解析文件
        log.info("开始下载并解析文件，ossKey={}", ossKey);
        String content = downloadAndParseFile(ossKey, kbDoc.getFilename());
        log.info("文件解析完成，内容长度={}字符", content.length());

        // 2. 文本清洗
        content = cleanText(content);

        // 3. 文本分块
        log.info("开始文本分块...");
        TokenTextSplitter splitter = new TokenTextSplitter(800, 350, 5, 10000, true);
        List<Document> chunks = splitter.split(new Document(content));
        log.info("文本分块完成，共{}个块", chunks.size());

        // 4. 向量化并存储到数据库
        log.info("开始向量化和存储...");
        saveChunksToDatabase(kbDoc.getId(), kbDoc.getFilename(), chunks);
        log.info("向量化和存储完成");

        // 5. 更新状态为完成
        kbDoc.setStatus("COMPLETED");
        documentRepository.save(kbDoc);
    }

    /**
     * 从OSS下载文件并解析内容
     */
    private String downloadAndParseFile(String ossKey, String filename) throws Exception {
        try (InputStream stream = storageService.getFileStream(ossKey)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Parser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            ParseContext context = new ParseContext();
            parser.parse(stream, handler, metadata, context);
            return handler.toString();
        }
    }

    /**
     * 文本清洗
     */
    private String cleanText(String content) {
        return content.replaceAll("\\n+", "\n");
    }

    /**
     * 保存文档块到数据库
     */
    private void saveChunksToDatabase(Long docId, String filename, List<Document> chunks) {
        transactionTemplate.execute(status -> {
            try {
                // 1. 清理旧数据
                jdbcClient.sql("DELETE FROM document_chunks WHERE doc_id = ?")
                        .param(docId)
                        .update();

                // 2. 批量插入
                int chunkIndex = 0;
                for (Document chunk : chunks) {
                    Map<String, Object> metadataMap = new HashMap<>();
                    metadataMap.put("source", "rabbitmq");
                    metadataMap.put("filename", filename);
                    metadataMap.put("file_id", docId);
                    metadataMap.put("chunk_index", chunkIndex++);

                    String metadataJson = objectMapper.writeValueAsString(metadataMap);
                    List<Double> embedding = embeddingModel.embed(chunk.getContent());

                    String sql = """
                          INSERT INTO document_chunks (doc_id, content, metadata, embedding)
                          VALUES (:docId, :content, :metadata::jsonb, :embedding::vector)
                      """;
                    jdbcClient.sql(sql)
                            .param("docId", docId)
                            .param("content", chunk.getContent())
                            .param("metadata", metadataJson)
                            .param("embedding", embedding.toString())
                            .update();
                }

                log.info("成功插入{}个文档块", chunks.size());
                return null;

            } catch (Exception e) {
                log.error("保存文档块失败", e);
                throw new RuntimeException("保存文档块失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 处理失败情况
     */
    private void handleFailure(Long docId, Exception e) {
        try {
            KbDocument doc = documentRepository.findById(docId).orElse(null);
            if (doc != null) {
                doc.setStatus("FAILED");
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.length() > 1000) {
                    errorMsg = errorMsg.substring(0, 1000) + "...";
                }
                doc.setErrorMessage(errorMsg);
                documentRepository.save(doc);
                log.info("已更新文档状态为FAILED，docId={}", docId);
            }
        } catch (Exception ex) {
            log.error("更新失败状态时出错", ex);
        }
    }
}