package org.example.rag.controller;

import org.springframework.ai.chat.client.ChatClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    private final ChatClient chatClient;

    public TestController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }
    @GetMapping("/hello")
    public String chat(@RequestParam(value= "message",defaultValue = "hello") String message) {
        return chatClient.prompt().user(message).call().content();
    }
}
