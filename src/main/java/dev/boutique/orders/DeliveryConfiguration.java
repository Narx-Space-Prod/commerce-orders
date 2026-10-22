package dev.boutique.orders;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@EnableScheduling
class DeliveryConfiguration {
    DeliveryConfiguration(@Value("${delivery.role}") String role) {
        if (!Set.of("api", "publisher", "worker").contains(role)) {
            throw new IllegalArgumentException("invalid_delivery_role");
        }
    }
    static void validateEndpoint(String queueUrl, String endpoint, boolean local) {
        if (queueUrl.isBlank()) throw new IllegalArgumentException("delivery_queue_url_required");
        URI queue = URI.create(queueUrl);
        if (local) {
            URI target = URI.create(endpoint);
            if (!"http".equals(target.getScheme())
                || !Set.of("localhost", "127.0.0.1", "elasticmq").contains(target.getHost())
                || !target.getScheme().equals(queue.getScheme())
                || !target.getAuthority().equals(queue.getAuthority())) {
                throw new IllegalArgumentException("local_queue_endpoint_required");
            }
        } else if (!endpoint.isBlank() || !"https".equals(queue.getScheme()) || queue.getHost() == null) {
            throw new IllegalArgumentException("aws_queue_requires_https_and_default_credentials");
        }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${delivery.role}' == 'publisher' or '${delivery.role}' == 'worker'")
    SqsClient queueClient(@Value("${delivery.queue-url}") String queueUrl,
                          @Value("${delivery.endpoint}") String endpoint,
                          @Value("${delivery.local}") boolean local,
                          @Value("${delivery.region}") String region) {
        validateEndpoint(queueUrl, endpoint, local);
        var builder = SqsClient.builder().region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(12)))
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))
                .apiCallAttemptTimeout(Duration.ofSeconds(12)));
        if (local) {
            // ElasticMQ only: deliberately fake credentials. AWS uses the default role chain.
            builder.endpointOverride(URI.create(endpoint)).credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }
}
