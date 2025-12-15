package org.example.rag.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * RAG服务接口
 */
public interface RagService {
    /**
     * 上传并处理文件
     * @param file
     * @return
     */
    String uploadAndProcess(MultipartFile file);
    /**
     * 删除知识库文档
     * @param documentId
     */
    void deleteDocument(Long documentId);

    /**
     * 根据用户查询相关内容,对话接口
     * @param query
     * @return
     */
    String chat(String query);
}
