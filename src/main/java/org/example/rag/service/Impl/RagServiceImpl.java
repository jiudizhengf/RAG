package org.example.rag.service.Impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.example.rag.common.UserContext;
import org.example.rag.entity.KbDocument;
import org.example.rag.repository.KbDocumentRepository;
import org.example.rag.service.RagService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.apache.tika.metadata.Metadata;

import org.springframework.ai.document.Document;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private final KbDocumentRepository kbDocumentRepository;
    private final EmbeddingModel embeddingModel;
    private final JdbcClient jdbcClient;
    private final Tika tika = new Tika();
    private final ChatClient.Builder chatClientBuilder;
    private final RedisTemplate<String,Object> redisTemplate;
    @Override
    public String uploadAndProcess(MultipartFile file) {
        //获取当前用户的权限组
        // 1. 获取当前用户信息
        Long userId = UserContext.getUserId();
        List<String> roles = UserContext.getRoles();

        if (userId == null || roles == null || roles.isEmpty()) {
            throw new RuntimeException("用户未登录或无权限");
        }
        String targetGroup = roles.get(0);
        //登记文件信息
        KbDocument kbDoc = new KbDocument();
        kbDoc.setFilename(file.getOriginalFilename());
        kbDoc.setFileSize(file.getSize());
        kbDoc.setFiletype(file.getContentType());
        kbDoc.setPermissionGroup(targetGroup);
        kbDoc.setStatus("PROCESSING");
        kbDoc = kbDocumentRepository.save(kbDoc);
        String content;
        try(InputStream stream = file.getInputStream()){
            //Tika 解析
            // -1 表示禁用字符数限制 (writeLimit)
            BodyContentHandler handler = new BodyContentHandler(-1);
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            parser.parse(stream, handler, metadata, context);

            content = handler.toString();

            if(content==null||content.isEmpty()){
                throw new RuntimeException("文件解析失败，内容为空");
            }
            //文本切片
            TokenTextSplitter splitter = new TokenTextSplitter(800,350,5,10000,true);
            List<Document> chunks = splitter.split(new Document(content));

            //向量化并且入库
            for(Document chunk:chunks){
                List<Double> embedding = embeddingModel.embed(chunk.getContent());
                //使用JdbcClient插入PGvector向量数据
                String insertSql= """
                     INSERT INTO document_chunks (doc_id, content, metadata, embedding)
                VALUES (:docId, :content, :metadata::jsonb, :embedding::vector)""";
                jdbcClient.sql(insertSql).param("docId",kbDoc.getId())
                        .param("content",chunk.getContent())
                        .param("metadata","{\"source\": \""+kbDoc.getFilename()+"\"}")
                        .param("embedding",embedding.toString())
                        .update();
                //更新日志
                kbDoc.setStatus("COMPLETED");
                kbDocumentRepository.save(kbDoc);
                //Thread.sleep(1000);

            }
            return "文件上传并处理成功,切片数量:"+chunks.size();
        } catch (Exception e) {
            log.error("处理文件时出错:", e);
            kbDoc.setStatus("FAILED");
            kbDoc.setErrorMessage(e.getMessage());
            kbDocumentRepository.save(kbDoc);
            throw new RuntimeException("文件处理失败"+e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackOn = Exception.class)
    public void deleteDocument(Long docId) {
        // 1. 先删子表（利用索引）
        int chunksDeleted = jdbcClient.sql("DELETE FROM document_chunks WHERE doc_id = ?")
                .param(docId)
                .update();

        // 2. 再删父表
        kbDocumentRepository.deleteById(docId);

        log.info("级联删除成功：文档ID={}, 关联切片数={}", docId, chunksDeleted);
    }

    @Override
    public String chat(String query) {
        List<String> userRoles = UserContext.getRoles();
        if(userRoles==null||userRoles.isEmpty()){
            return "用户未登录或无权限。";
        }
        //构建Redis缓存
        // 格式：chat:{角色哈希}:{问题哈希}
        // 为什么要把角色加进去？防止 HR 问完答案被缓存，研发问同样问题查到了 HR 的答案
        String roleKey = userRoles.stream().sorted().reduce("",String::concat);
        String cacheKey = "chat:"+roleKey.hashCode()+":"+query.hashCode();
        //先查缓存
        Object cachedAnswer = redisTemplate.opsForValue().get(cacheKey);
        if(cachedAnswer!=null){
            log.info("命中缓存，key={}",cacheKey);
            return cachedAnswer.toString();
        }
        //把用户的问题变成向量
        List<Double> queryEmbedding = embeddingModel.embed(query);
        //在向量数据库中检索最相似的top5片段
        // 核心逻辑：JOIN 文档表，筛选 permission_group 在用户 roles 列表里的
        String searchSql = """
            SELECT dc.content
            FROM document_chunks dc
            JOIN kb_documents kd ON dc.doc_id = kd.id
            WHERE kd.permission_group IN (:userRoles)
            ORDER BY dc.embedding <-> :embedding::vector
            LIMIT 3
        """;
        //执行查询
        List<String> relatedContexts = jdbcClient.sql(searchSql)
                .param("embedding",queryEmbedding.toString())
                .param("userRoles",userRoles)
                .query(String.class)
                .list();
        //如果没查询到相关内容直接返回
        if(relatedContexts.isEmpty()){
            return "未能找到相关内容。";
        }
        //构建提示词
        String context = String.join("\n\n",relatedContexts);
        String prompt = """
            你是一个专业的企业级知识助手。
            请仅根据以下提供的[参考资料]来回答用户的[问题]。
            如果[参考资料]中没有包含答案，请直接回答“我不知道”，不要编造信息。
            
            [参考资料]：
            {context}
            
            [问题]：
            {question}
            """;
        //替换模板中的变量
        PromptTemplate template = new PromptTemplate(prompt);
        Prompt prompt1 = template.create(Map.of(
                "context", context,
                "question", query
        ));
        //调用AI生成回答
        ChatClient chatClient = chatClientBuilder.build();
        String finalAnswer =  chatClient.prompt(prompt1).call().content();
        //存入缓存，设置过期时间60分钟
        redisTemplate.opsForValue().set(cacheKey,finalAnswer, Duration.ofHours(1));
        log.info("存入缓存，key={}",cacheKey);
        return finalAnswer;
    }
}
