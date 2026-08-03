package ai.rojan.backend.infrastructure.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JsonSerializer

/**
 * Kafka producer wiring for future domain-event publishing ("prepare
 * integration" per the initial scope). No topic/producer is invoked yet —
 * this is real, connectable configuration driven by spring.kafka.* properties.
 */
@Configuration
class KafkaProducerConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
) {

    @Bean
    fun producerFactory(): ProducerFactory<String, Any> {
        val configs = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        )
        return DefaultKafkaProducerFactory(configs)
    }

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> =
        KafkaTemplate(producerFactory)
}
