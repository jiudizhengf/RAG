package org.example.rag.controller;

import lombok.RequiredArgsConstructor;
import org.example.rag.common.Result;
import org.example.rag.service.RagService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocumentController {

    private final RagService ragService;
    @PostMapping("/upload")
    public Result<String> uploadDocument(@RequestParam("file") MultipartFile file) {
        String processResult = ragService.uploadAndProcess(file);
        return Result.success(processResult);
    }

    @DeleteMapping("/delete/{id}")
    public Result<String> deleteDocument(@PathVariable("id") Long documentId) {
        ragService.deleteDocument(documentId);
        return Result.success("删除成功");
    }
}
