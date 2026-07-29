package com.smartcampus.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 * 配置秒杀订单相关的交换机、队列和绑定关系
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    /**
     * 秒杀订单交换机名称
     */
    public static final String SECKILL_EXCHANGE = "seckill.topic";

    /**
     * 秒杀订单队列名称
     */
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    /**
     * 秒杀订单死信队列名称
     */
    public static final String SECKILL_ORDER_DLQ = "seckill.order.dlq";

    /**
     * 路由键
     */
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    /**
     * 死信路由键
     */
    public static final String SECKILL_ORDER_DLQ_ROUTING_KEY = "seckill.order.dlq";

    /**
     * 死信交换机
     */
    public static final String SECKILL_DLX_EXCHANGE = "seckill.dlx";

    /**
     * 配置消息转换器，使用 JSON 格式
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置 RabbitTemplate：
     * 1. mandatory=true：消息路由到 Queue 失败时触发 Return 回调（而非静默丢弃）
     * 2. ConfirmCallback：消息到达 Exchange 失败时记录错误日志
     * 3. ReturnsCallback：消息从 Exchange 路由到 Queue 失败时记录错误日志
     *
     * 前提：application.yaml 中需开启
     * publisher-confirm-type: correlated
     * publisher-returns: true
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        // 消息路由到 Queue 失败时，将消息返回给生产者（触发 ReturnsCallback）
        rabbitTemplate.setMandatory(true);

        // ① 生产者 Confirm 回调：确认消息是否成功到达 Exchange
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("[RabbitMQ] 消息未到达 Exchange，correlationData={}, cause={}",
                        correlationData, cause);
                // TODO: 可在此处将失败消息记录到 DB 补偿表，或触发重试
            } else {
                log.debug("[RabbitMQ] 消息成功到达 Exchange，correlationData={}", correlationData);
            }
        });

        // ② 生产者 Return 回调：消息到达 Exchange 但路由不到 Queue 时触发
        // Spring AMQP 3.x / Spring Boot 3 使用 ReturnedMessage 聚合返回信息。
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("[RabbitMQ] 消息路由到 Queue 失败，exchange={}，routingKey={}，replyCode={}，replyText={}，message={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(),
                    returned.getReplyText(), returned.getMessage());
            // TODO: 可在此处将失败消息落库，人工补偿
        });

        return rabbitTemplate;
    }

    /**
     * 声明死信交换机
     */
    @Bean
    public Exchange dlxExchange() {
        return ExchangeBuilder.topicExchange(SECKILL_DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 声明死信队列
     */
    @Bean
    public Queue dlQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_DLQ).build();
    }

    /**
     * 绑定死信队列到死信交换机
     */
    @Bean
    public Binding dlBinding() {
        return BindingBuilder.bind(dlQueue())
                .to(dlxExchange())
                .with(SECKILL_ORDER_DLQ_ROUTING_KEY)
                .noargs();
    }

    /**
     * 声明秒杀订单交换机（Topic类型）
     */
    @Bean
    public Exchange seckillExchange() {
        return ExchangeBuilder.topicExchange(SECKILL_EXCHANGE)
                .durable(true) // 持久化
                .build();
    }

    /**
     * 声明秒杀订单队列（持久化，配置死信队列）
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .deadLetterExchange(SECKILL_DLX_EXCHANGE) // 配置死信交换机
                .deadLetterRoutingKey(SECKILL_ORDER_DLQ_ROUTING_KEY) // 死信路由键
                .build();
    }

    /**
     * 绑定队列到交换机
     */
    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillExchange())
                .with(SECKILL_ORDER_ROUTING_KEY)
                .noargs();
    }
}
