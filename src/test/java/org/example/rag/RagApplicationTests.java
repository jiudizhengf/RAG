package org.example.rag;

import lombok.RequiredArgsConstructor;
import org.example.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.io.FileInputStream;

@SpringBootTest
class RagApplicationTests {

    @Autowired
    private RagService ragService;

    @Test
    void contextLoads() {
    }
    @Test
    void testBatchUpload() throws Exception {
        // 1. 指定你本地存放大量文档的文件夹路径
        // 注意：Windows 路径要是双斜杠，例如 "D:\\Learning\\JavaDocs"
        File folder = new File("C:\\Users\\21154\\Desktop\\上传文件");

        File[] files = folder.listFiles();
        if (files == null) return;

        int successCount = 0;

        System.out.println("开始批量上传，共扫描到 " + files.length + " 个文件...");

        for (File file : files) {
            // 只处理文件，不处理子文件夹
            if (file.isFile()) {
                // 模拟 MultipartFile
                FileInputStream input = new FileInputStream(file);
                MockMultipartFile mockFile = new MockMultipartFile(
                        "file",
                        file.getName(),
                        "application/pdf", // 或者是 text/plain，根据实际情况
                        input
                );

                try {
                    // 调用 Service 上传
                    String result = ragService.uploadAndProcess(mockFile);
                    System.out.println("✅ 上传成功: " + file.getName() + " -> " + result);
                    successCount++;
                } catch (Exception e) {
                    System.err.println("❌ 上传失败: " + file.getName() + " -> " + e.getMessage());
                } finally {
                    input.close();
                }
            }
        }
        System.out.println("批量处理结束，成功入库: " + successCount + " 个文件");
    }
}
