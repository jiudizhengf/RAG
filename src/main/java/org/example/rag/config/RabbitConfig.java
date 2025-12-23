package org.example.rag.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;


@Configuration
public class RabbitConfig {
    //队列名称
    public static final String RAG_UPLOAD_QUEUE = "rag.upload.queue";
    //交换机名称
    public static final String RAG_UPLOAD_EXCHANGE = "rag.upload.exchange";
    //路由键
    public static final String RAG_ROUTING_KEY = "rag.upload";
    //死信流程定义
    public static final String RAG_DL_QUEUE="rag.upload.dlq";
    //死信交换机
    public static final String RAG_DL_EXCHANGE = "rag.upload.dlx";
    //死信路由键
    public static final String RAG_DL_ROUTING_KEY="rag.upload.dlk";

    /**
     * 定义死信交换机
     * @return
     */
    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(RAG_DL_EXCHANGE,true,false);
    }
    /**
     * 定义死信队列
     * @return
     */
    @Bean
    public Queue deadLetterQueue(){
        return new Queue(RAG_DL_QUEUE,true);
    }
    /**
     * 死信队列绑定交换机
     * @return
     */
    @Bean
    public Binding deadLetterBinding(){
        return BindingBuilder.bind(deadLetterQueue()).to(directExchange()).with(RAG_DL_ROUTING_KEY);
    }

    /**
     * 定义业务交换机
     * @return
     */
    @Bean
    public DirectExchange uploadExchange() {
        return new DirectExchange(RAG_UPLOAD_EXCHANGE,true,false);
    }

    /**
     * 定义业务队列
     * @return
     */
    @Bean
    public Queue uploadQueue(){
        Map<String,Object> args = new HashMap<>();
        //指定死信交换机和路由键
        args.put("x-dead-letter-exchange",RAG_DL_EXCHANGE);
        args.put("x-dead-letter-routing-key",RAG_DL_ROUTING_KEY);
        //持久化队列
        return new Queue(RAG_UPLOAD_QUEUE,true,false,false,args);
    }
    /**
     * 业务队列绑定交换机
     * @return
     */
    @Bean
    public Binding uploadBinding(){
        return BindingBuilder.bind(uploadQueue())
                .to(uploadExchange())
                .with(RAG_ROUTING_KEY);
    }
    //发送时自动把对象转成 JSON，接收时自动把 JSON 转回对象
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
