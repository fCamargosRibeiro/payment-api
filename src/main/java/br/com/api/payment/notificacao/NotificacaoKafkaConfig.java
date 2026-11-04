package br.com.api.payment.notificacao;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.internals.ShareAcknowledgementMode;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ShareKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultShareConsumerFactory;
import org.springframework.kafka.core.ShareConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.ShareAckMode;

import java.util.HashMap;
import java.util.Map;

@Configuration
class NotificacaoKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:29092}")
    private String bootstrapServers;

    @Bean
    public ShareConsumerFactory<String, String> shareConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.SHARE_ACKNOWLEDGEMENT_MODE_CONFIG, ShareAcknowledgementMode.EXPLICIT);
        return new DefaultShareConsumerFactory<>(props);
    }

    @Bean
    public ShareKafkaListenerContainerFactory<String, String> shareKafkaListenerContainerFactory(ShareConsumerFactory<String, String> shareConsumerFactory) {
        ShareKafkaListenerContainerFactory<String, String> factory = new ShareKafkaListenerContainerFactory<>(shareConsumerFactory);
        factory.getContainerProperties().setShareAckMode(ShareAckMode.MANUAL);
        return factory;
    }
}
