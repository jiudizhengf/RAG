package org.example.rag.service.Impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import lombok.extern.slf4j.Slf4j;
import org.example.rag.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
@Service
@Slf4j
public class AliyunOssStorageService implements StorageService {
    @Value("${aliyun.oss.endpoint}")
    private String endpoint;
    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;
    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;
    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;
    @Override
    public String upload(String objectName, InputStream inputStream) {
        OSS ossClient = null;
        try {
            // 创建OSSClient实例
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            //上传文件
            ossClient.putObject(bucketName, objectName, inputStream);
            log.info("文件上传成功，objectName: {}",objectName);
            return objectName;
        }catch (Exception e){
            log.error("创建OSSClient实例失败:",e);
            throw new RuntimeException("文件上传失败"+e.getMessage());
        }finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    @Override
    public InputStream getFileStream(String objectName) {
        OSS ossClient = null;
        try{
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            OSSObject ossObject = ossClient.getObject(bucketName, objectName);
            return ossObject.getObjectContent();
        }catch (Exception e){
            log.error("获取文件流失败:",e);
            throw new RuntimeException("获取文件流失败"+e.getMessage());
        }

    }

    @Override
    public void delete(String objectName) {
        OSS ossClient = null;
        try{
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            ossClient.deleteObject(bucketName, objectName);
            log.info("文件删除成功，objectName: {}",objectName);
        }catch (Exception e){
            log.error("删除文件失败:",e);
            throw new RuntimeException("删除文件失败"+e.getMessage());
        }finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }

    }
}
