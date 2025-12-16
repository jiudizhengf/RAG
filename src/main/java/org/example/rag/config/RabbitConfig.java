package org.example.rag.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class RabbitConfig {
    //队列名称
    public static final String RAG_UPLOAD_QUEUE = "rag.upload.queue";
    @Bean
    public Queue uploadQueue(){
        //持久化队列
        return new Queue(RAG_UPLOAD_QUEUE,true);
    }
    //发送时自动把对象转成 JSON，接收时自动把 JSON 转回对象
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
