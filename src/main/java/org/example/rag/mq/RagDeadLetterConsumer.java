package org.example.rag.mq;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RabbitConfig;
import org.example.rag.entity.dto.DocUploadMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class RagDeadLetterConsumer {

    @RabbitListener(queues = RabbitConfig.RAG_DL_QUEUE)
    public void processDeadLetter(DocUploadMessage msg, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag)  {

        // 在这里添加处理死信消息的逻辑，例如记录日志、报警等
        try{
            log.error("收到死信队列消息，文档ID: {}, 可能处理失败多次，请检查相关问题。", msg.getDocId());
            // 手动确认消息已被处理
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("处理死信消息时发生异常，文档ID: {}", msg.getDocId(), e);
            try {
                // 拒绝消息并不重新入队
                channel.basicAck(tag, false);
            } catch (IOException ioException) {
                log.error("拒绝死信消息时发生异常，文档ID: {}", msg.getDocId(), ioException);
            }
        }
    }
}
