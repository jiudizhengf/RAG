package org.example.rag.mq;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
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
import org.springframework.messaging.handler.annotation.Header;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RabbitConfig;
import org.example.rag.entity.dto.DocUploadMessage;
import org.example.rag.repository.KbDocumentRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagConsumer {
    private final KbDocumentRepository documentRepository;
    private final EmbeddingModel embeddingModel;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper; // Spring Boot 自动注入
    //redis幂等性检查
    private final RedisTemplate<String, Object> redisTemplate;
    //事务模版，保证向量入库一致性
    private final TransactionTemplate transactionTemplate;
    private final StorageService storageService;

    @RabbitListener(queues = RabbitConfig.RAG_UPLOAD_QUEUE, concurrency = "5-10")
    public void processUpload(DocUploadMessage msg, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        //定义唯一的业务key，防止重复处理
        String distinctKey = "rag:process:" + msg.getDocId();
        try {
            log.info("收到MQ消息，开始处理文件上传，文档ID: {}", msg.getDocId());
            //幂等性检查
            Boolean isAbsent = redisTemplate.opsForValue().setIfAbsent(distinctKey, "PROCESSING", Duration.ofHours(1));
            if (Boolean.FALSE.equals(isAbsent)) {
                log.warn("检测到重复处理请求，文档ID: {}", msg.getDocId());
                //手动ACK
                channel.basicAck(tag, false);
                return;
            }
            //检查数据库状态
            KbDocument kbDoc = documentRepository.findById(msg.getDocId()).orElse(null);
            if (kbDoc == null) {
                log.error("无法找到文档记录ID: {}", msg.getDocId());
                //数据不一致,视为不可恢复错误，手动ACK，避免死循环
                channel.basicAck(tag, false);
                //丢弃消息，删掉幂等Key
                redisTemplate.delete(distinctKey);
                return;
            }
            //更新状态为处理中
            kbDoc.setStatus("PROCESSING");
            documentRepository.save(kbDoc);
            processDocumentLogic(kbDoc, msg);
            //手动ACK
            channel.basicAck(tag, false);
            log.info("文件处理完成，文档ID: {}", msg.getDocId());
        } catch (Exception e) {
            log.error("处理文件上传失败，文档ID: {}, 错误信息: {}", msg.getDocId(), e.getMessage(), e);
            handleFailure(msg.getDocId(),e);
            try {
                //错误处理（死信路由）
                //拒绝消息并且不重回队列
                channel.basicReject(tag,false);
            }catch (Exception ex){
                log.error("拒绝消息失败，文档ID: {}, 错误信息: {}", msg.getDocId(), ex.getMessage(), ex);
            }
        }finally {
            //释放幂等Key
            redisTemplate.delete(distinctKey);
        }
    }

    /**
     * 处理文档的核心逻辑
     *
     * @param kbDoc
     * @param msg
     * @throws Exception
     */
    private void processDocumentLogic(KbDocument kbDoc, DocUploadMessage msg) throws Exception {
        String ossKey = msg.getLocalFilePath();

        //Tika解析
        String content;
        try (InputStream stream = storageService.getFileStream(ossKey)) {
            BodyContentHandler bodyContentHandler = new BodyContentHandler(-1);
            Parser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, kbDoc.getFilename());
            ParseContext context = new ParseContext();
            parser.parse(stream, bodyContentHandler, metadata, context);
            content = bodyContentHandler.toString();
        }
        // 简单的清洗 (去掉多余空行)
        content = content.replaceAll("\\n+", "\n");
        //切分
        TokenTextSplitter splitter = new TokenTextSplitter(800, 350, 5, 10000, true);
        List<Document> chunks = splitter.split(new Document(content));
        //向向量数据库插入

        //使用事务进行批量插入，保证一致性
        transactionTemplate.execute(status -> {
            try {
                //清洗旧数据
                jdbcClient.sql("DELETE FROM document_chunks WHERE doc_id = ?")
                        .param(msg.getDocId())
                        .update();
                //遍历切片插入
                int chunkIndex = 0;
                for (Document chunk : chunks) {
                    // 1. 动态构建 Metadata
                    Map<String, Object> metadataMap = new HashMap<>();
                    metadataMap.put("source", "rabbitmq"); // 保留来源标识
                    metadataMap.put("filename", kbDoc.getFilename()); // ✅ 关键：原文件名
                    metadataMap.put("file_id", kbDoc.getId()); // 文件 ID
                    metadataMap.put("chunk_index", chunkIndex++);
                    // 2. 转成 JSON 字符串
                    String metadataJson = objectMapper.writeValueAsString(metadataMap);
                    List<Double> embedding = embeddingModel.embed(chunk.getContent());
                    String sql = """
                                INSERT INTO document_chunks (doc_id, content, metadata, embedding)
                                VALUES (:docId, :content, :metadata::jsonb, :embedding::vector)
                            """;
                    jdbcClient.sql(sql)
                            .param("docId", msg.getDocId())
                            .param("content", chunk.getContent())
                            .param("metadata", metadataJson)
                            .param("embedding", embedding.toString())
                            .update();
                }
            } catch (Exception e) {
                throw new RuntimeException("向量插入失败: " + e.getMessage(), e);
            }
            return null;
        });
        //更新状态为完成
        kbDoc.setStatus("COMPLETED");
        documentRepository.save(kbDoc);

    }
    /**
     * 辅助方法：记录失败原因
     */
    private void handleFailure(Long docId, Exception e) {
        try {
            KbDocument kbDoc = documentRepository.findById(docId).orElse(null);
            if (kbDoc != null) {
                kbDoc.setStatus("FAILED");
                // 截取错误信息防止爆字段
                String errorMsg = e.getMessage();
                if(errorMsg != null && errorMsg.length() > 1000) errorMsg = errorMsg.substring(0, 1000);
                kbDoc.setErrorMessage(errorMsg);
                documentRepository.save(kbDoc);
            }
        } catch (Exception ex) {
            log.error("更新失败状态出错", ex);
        }
    }

}
