package org.example.rag.service.Impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.example.rag.common.UserContext;
import org.example.rag.config.RabbitConfig;
import org.example.rag.entity.KbDocument;
import org.example.rag.entity.dto.DocUploadMessage;
import org.example.rag.repository.KbDocumentRepository;
import org.example.rag.service.CacheManager;
import org.example.rag.service.RagService;
import org.example.rag.service.StorageService;
import org.example.rag.service.VectorSearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private final KbDocumentRepository kbDocumentRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final RabbitTemplate rabbitTemplate;
    private final StorageService storageService;
    private final CacheManager cacheManager;
    private final VectorSearchService vectorSearchService;
    /**
     * 缓存过期时间：1小时
     */
    private static final int CACHE_EXPIRE_HOURS = 1;

    /**
     * RAG提示词模板
     */
    private static final String RAG_PROMPT_TEMPLATE = """
            你是一个专业的企业级知识助手。
            请仅根据以下提供的[参考资料]来回答用户的[问题]。
            如果[参考资料]中没有包含答案，请直接回答"我不知道"，不要编造信息。
            
            [参考资料]:
            {context}
            
            [问题]:
            {question}
            """;

    @Override
    @Transactional
    public String uploadAndProcess(MultipartFile file) {
        //获取当前用户的权限组
        // 1. 获取当前用户信息
        Long userId = UserContext.getUserId();
        List<String> roles = UserContext.getRoles();

        // 2. 验证用户身份
        validateUser(userId, roles);
        String targetGroup = roles.get(0);
        KbDocument kbDoc = new KbDocument();
        try {
            //计算文件MD5
            String fileHash = calculateFileHash(file);

            //检测是否重复上传
            if (isFileDuplicate(fileHash, targetGroup)) {
                log.warn("文件重复上传检测，用户ID={}, 文件Hash={}, 权限组={}", userId, fileHash, targetGroup);
                return "文件已存在，禁止重复上传";
            }
            //上传到OSS
            String ossKey = uploadToOSS(file, fileHash, targetGroup);
            log.info("文件上传到OSS成功，用户ID={}, OSS对象名={}", userId, ossKey);

            //登记文件信息
            kbDoc = saveDocumentRecord(file, ossKey, fileHash, targetGroup);
            log.info("文件信息保存到数据库成功，用户ID={}, 文档ID={}", userId, kbDoc.getId());

            //发送消息到消息队列
            sendProcessMessage(kbDoc.getId(), ossKey, userId, targetGroup);
            log.info("发送文件处理消息到MQ成功，用户ID={}, 文档ID={}", userId, kbDoc.getId());
            return "文件上传成功，正在后台处理";
        } catch (Exception e) {
            log.error("处理文件时出错:", e);
            handleUploadFailure(kbDoc, e);
            throw new RuntimeException("文件处理失败" + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackOn = Exception.class)
    public void deleteDocument(Long docId) {
        log.info("删除文档，docId={}", docId);

        // 1. 先删除文档片段（子表）
        int chunksDeleted = kbDocumentRepository.deleteChunksByDocId(docId);
        log.debug("已删除文档片段数={}", chunksDeleted);
        KbDocument doc = kbDocumentRepository.findById(docId).orElse(null);
        if (doc == null) {
            log.warn("文档不存在，docId={}", docId);
            return;
        }

        String ossKey = doc.getFilepath();

        // 2. 再删除文档（主表）
        kbDocumentRepository.deleteById(docId);
        if(ossKey!=null&&!ossKey.isEmpty()){
            storageService.delete(ossKey);
            log.debug("已从对象存储中删除文档记录，docId={}", docId);
        }else{
            log.warn("文档OSS路径为空，无法删除文件，docId={}", docId);
            return;
        }

        log.info("文档删除成功，docId={}, 删除片段数={}", docId, chunksDeleted);
    }

    @Override
    public String chat(String query) {
        List<String> userRoles = UserContext.getRoles();
        if (userRoles == null || userRoles.isEmpty()) {
            return "用户未登录或无权限。";
        }
        //构建Redis缓存
        // 格式：chat:{角色哈希}:{问题哈希}
        // 为什么要把角色加进去？防止 HR 问完答案被缓存，研发问同样问题查到了 HR 的答案
        String cacheKey = generateCacheKey(userRoles, query);
        log.debug("缓存键={}", cacheKey);
        //先查缓存
        String cachedAnswer = cacheManager.get(cacheKey);
        if (cachedAnswer != null) {
            log.info("缓存命中，key={}", cacheKey);
            return cachedAnswer;
        }
        //缓存未命中，进行RAG流程
        List<String> contexts = vectorSearchService.search(query, userRoles);
        if (contexts.isEmpty()) {
            return "未能找到相关内容。";
        }
        //调用LLM生成答案
        String answer = generateAnswer(query, contexts);
        log.info("LLM生成答案成功");
        //写入缓存
        cacheManager.put(cacheKey, answer, Duration.ofHours(CACHE_EXPIRE_HOURS));
        log.info("答案已缓存，key={}", cacheKey);
        return answer;
    }

    /**
     * 验证用户身份
     */
    private void validateUser(Long userId, List<String> roles) {
        if (userId == null || roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("用户未登录或无权限");
        }
    }

    /**
     * 计算文件MD5
     */
    private String calculateFileHash(MultipartFile file) throws Exception {
        try (InputStream is = file.getInputStream()) {
            return DigestUtils.md5Hex(is);
        }
    }

    /**
     * 检查文件是否重复
     */
    private boolean isFileDuplicate(String fileHash, String permissionGroup) {
        return kbDocumentRepository.existsByFileHashAndPermissionGroup(fileHash, permissionGroup);
    }

    /**
     * 上传文件到OSS
     */
    private String uploadToOSS(MultipartFile file, String fileHash, String permissionGroup) throws Exception {
        String objectName = String.format("rag-docs/%s/%s_%s",
                permissionGroup, fileHash, file.getOriginalFilename());

        try (InputStream is = file.getInputStream()) {
            storageService.upload(objectName, is);
        }

        return objectName;
    }

    /**
     * 保存文档记录
     */
    private KbDocument saveDocumentRecord(MultipartFile file, String ossKey,
                                          String fileHash, String permissionGroup) {
        KbDocument doc = new KbDocument();
        doc.setFilename(file.getOriginalFilename());
        doc.setFileSize(file.getSize());
        doc.setFiletype(file.getContentType());
        doc.setPermissionGroup(permissionGroup);
        doc.setFileHash(fileHash);
        doc.setFilepath(ossKey);
        doc.setStatus("PENDING");
        return kbDocumentRepository.save(doc);
    }

    /**
     * 发送处理消息到MQ
     */
    private void sendProcessMessage(Long docId, String ossKey, Long userId, String permissionGroup) {
        DocUploadMessage message = new DocUploadMessage(docId, ossKey, userId, permissionGroup);
        rabbitTemplate.convertAndSend(
                RabbitConfig.RAG_UPLOAD_EXCHANGE,
                RabbitConfig.RAG_ROUTING_KEY,
                message
        );
    }

    /**
     * 处理上传失败
     */
    private void handleUploadFailure(KbDocument doc, Exception e) {
        if (doc != null) {
            doc.setStatus("FAILED");
            doc.setErrorMessage(e.getMessage());
            kbDocumentRepository.save(doc);
        }
    }

    /**
     * 生成缓存键
     */
    private String generateCacheKey(List<String> userRoles, String query) {
        // 1. 角色排序并拼接
        String roleKey = userRoles.stream()
                .sorted()
                .reduce("", String::concat);

        // 2. 计算角色哈希
        String roleHash = DigestUtils.md5Hex(roleKey);

        // 3. 计算问题哈希
        String queryHash = DigestUtils.sha256Hex(query);

        // 4. 生成缓存键
        return cacheManager.generateKey("chat", roleHash, queryHash);
    }

    /**
     * 调用LLM生成答案
     */
    private String generateAnswer(String query, List<String> contexts) {
        // 1. 构建上下文
        String context = String.join("\n\n", contexts);

        // 2. 构建提示词
        PromptTemplate template = new PromptTemplate(RAG_PROMPT_TEMPLATE);
        Prompt prompt = template.create(Map.of(
                "context", context,
                "question", query
        ));

        // 3. 调用LLM
        ChatClient chatClient = chatClientBuilder.build();
        return chatClient.prompt(prompt).call().content();
    }
}
