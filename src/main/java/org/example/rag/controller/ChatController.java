package org.example.rag.controller;

import lombok.RequiredArgsConstructor;

import org.example.rag.common.Result;
import org.example.rag.entity.dto.ChatRequest;
import org.example.rag.service.RagService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {
    private final RagService ragService;

    @PostMapping
    public Result<String> chat(@RequestBody ChatRequest query) {
        //校验参数
        if (query == null||query.getQuestion()==null||query.getQuestion().trim().isEmpty()) {
            return Result.failed("问题不能为空");
        }
        String question = query.getQuestion();
        String answer = ragService.chat(question);
        return  Result.success(answer);
    }
}
