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
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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

    @RabbitListener(queues = RabbitConfig.RAG_UPLOAD_QUEUE)
    public void processUpload(DocUploadMessage msg, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag){
        log.info("收到MQ消息，开始处理文件上传，文档ID: {}",msg.getDocId());
        KbDocument kbDoc=documentRepository.findById(msg.getDocId()).orElse(null);
        if(kbDoc==null){
            log.error("无法找到文档记录ID: {}",msg.getDocId());
            return;
        }
        try{
            //更新状态为处理中
            kbDoc.setStatus("PROCESSING");
            documentRepository.save(kbDoc);
            //读取本地文件
            File file =new File(msg.getLocalFilePath());
            if(!file.exists()){
                throw new RuntimeException("文件不存在: "+msg.getLocalFilePath());
            }
            //Tika解析
            String content;
            try(InputStream stream = new FileInputStream(file)){
                BodyContentHandler bodyContentHandler = new BodyContentHandler(-1);
                Parser parser = new AutoDetectParser();
                Metadata metadata = new Metadata();
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY,file.getName());
                ParseContext context = new ParseContext();
                parser.parse(stream,bodyContentHandler,metadata,context);
                content = bodyContentHandler.toString();
            }
            // 简单的清洗 (去掉多余空行)
            content = content.replaceAll("\\n+", "\n");
            //切分
            TokenTextSplitter splitter = new TokenTextSplitter(800,350,5,10000,true);
            List<Document> chunks = splitter.split(new Document(content));
            //向向量数据库插入
            int chunkIndex = 0;
            for(Document chunk:chunks){
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
            //更新状态为完成
            kbDoc.setStatus("COMPLETED");
            documentRepository.save(kbDoc);
            log.info("文档处理完成，ID: {}",msg.getDocId());
            //删掉临时文件
            if(file.exists()){
                file.delete();
            }
            //手动ACK
            channel.basicAck(tag,false);
            log.info("消息ACK完成，ID: {}",msg.getDocId());
        } catch (Exception e) {
            log.error("处理文档ID: {} 出错: {}",msg.getDocId(),e.getMessage());
            //更新状态为失败
            kbDoc.setStatus("FAILED");
            kbDoc.setErrorMessage(e.getMessage());
            documentRepository.save(kbDoc);
            try {
                //拒绝消息，不重新入队
                channel.basicReject(tag,false);
                log.info("消息拒绝完成，ID: {}",msg.getDocId());
            } catch (Exception ex) {
                log.error("拒绝消息失败，ID: {} 错误: {}",msg.getDocId(),ex.getMessage());
            }
        }

    }

}
