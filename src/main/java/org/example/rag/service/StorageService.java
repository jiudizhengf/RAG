package org.example.rag.service;

import java.io.InputStream;

/**
 * 存储服务接口
 */
public interface StorageService {
    /**
     * 上传文件
     * @param objectName
     * @param inputStream
     * @return
     */
    String upload(String objectName, InputStream inputStream);

    /**
     * 获取文件流
     * @param objectName
     * @return
     */
    InputStream getFileStream(String objectName);

    /**
     * 删除文件
     * @param objectName
     */
    void delete(String objectName);
}
