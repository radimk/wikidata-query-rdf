package org.wikidata.query.rdf.tool;

import static org.wikidata.query.rdf.tool.HttpClientUtils.buildHttpClient;
import static org.wikidata.query.rdf.tool.HttpClientUtils.buildHttpClientRetryer;
import static org.wikidata.query.rdf.tool.HttpClientUtils.getHttpProxyHost;
import static org.wikidata.query.rdf.tool.HttpClientUtils.getHttpProxyPort;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.openrdf.rio.RDFParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.query.rdf.tool.options.OptionsUtils;
import org.wikidata.query.rdf.tool.options.StreamingUpdateOptions;
import org.wikidata.query.rdf.tool.rdf.RDFParserSuppliers;
import org.wikidata.query.rdf.tool.rdf.RdfRepositoryUpdater;
import org.wikidata.query.rdf.tool.rdf.client.RdfClient;
import org.wikidata.query.rdf.tool.stream.KafkaStreamConsumer;
import org.wikidata.query.rdf.tool.stream.KafkaStreamConsumerMetricsListener;
import org.wikidata.query.rdf.tool.stream.MutationEventData;
import org.wikidata.query.rdf.tool.stream.RDFChunkDeserializer;
import org.wikidata.query.rdf.tool.stream.StreamingUpdater;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jmx.JmxReporter;
import com.github.rholder.retry.Retryer;

public final class StreamingUpdate {
    private static final Logger log = LoggerFactory.getLogger(StreamingUpdate.class);

    private StreamingUpdate() {}

    public static void main(String[] args) {
        log.info("Starting StreamingUpdater");
        StreamingUpdateOptions options = OptionsUtils.handleOptions(StreamingUpdateOptions.class, args);
        MetricRegistry metrics = new MetricRegistry();
        JmxReporter reporter = JmxReporter.forRegistry(metrics).inDomain(options.metricDomain()).build();
        StreamingUpdater updater = build(options, metrics);
        Thread streamingUpdaterThread = Thread.currentThread();
        streamingUpdaterThread.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception in the updater thread: ", e));
        addShutdownHook(updater, streamingUpdaterThread, reporter);
        updater.run();
    }

    static void addShutdownHook(StreamingUpdater updater, Thread updaterThread, JmxReporter reporter) {
        Thread t = new Thread(() -> {
            updater.close();
            try {
                updaterThread.join(2000);
            } catch (InterruptedException e) {
                log.error("Failed to stop the streaming updater", e);
            }
            if (updaterThread.isAlive()) {
                log.warn("Failed to stop the streaming updater cleanly.");
            }
            reporter.close();
        }, "StreamingUpdate shutdown");
        Runtime.getRuntime().addShutdownHook(t);
    }

    static StreamingUpdater build(StreamingUpdateOptions options, MetricRegistry metrics) {
        RDFChunkDeserializer deser = new RDFChunkDeserializer(new RDFParserSuppliers(RDFParserRegistry.getInstance()));

        KafkaStreamConsumer consumer = KafkaStreamConsumer.build(options.brokers(),
                options.topic(),
                options.partition(),
                options.consumerGroup(),
                options.batchSize(),
                deser,
                parseInitialOffset(options),
                KafkaStreamConsumerMetricsListener.forRegistry(metrics));

        HttpClient httpClient = buildHttpClient(getHttpProxyHost(), getHttpProxyPort());
        Retryer<ContentResponse> retryer = buildHttpClientRetryer();
        Duration rdfClientTimeout = RdfRepositoryUpdater.getRdfClientTimeout();
        RdfClient rdfClient = new RdfClient(httpClient, StreamingUpdateOptions.sparqlUri(options), retryer, rdfClientTimeout);
        return new StreamingUpdater(consumer, new RdfRepositoryUpdater(rdfClient), metrics);
    }

    static String RESET_OFFSETS_TO_EARLIEST = "earliest";

    private static BiConsumer<Consumer<String, MutationEventData>, TopicPartition> parseInitialOffset(StreamingUpdateOptions options) {
        String initialOffsets = options.initialOffsets();
        if (RESET_OFFSETS_TO_EARLIEST.equals(initialOffsets)) {
            return null;
        }
        try {
            if (initialOffsets.matches("^[0-9]+$")) {
                return KafkaStreamConsumer.resetToOffset(Long.parseLong(initialOffsets));
            }
            return KafkaStreamConsumer.resetToTime(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(initialOffsets)));
        } catch (IllegalArgumentException iae) {
            throw new IllegalArgumentException("Cannot parse initial offset: [" + initialOffsets + "], " +
                    "must be 'earliest', a number or a date", iae);
        }
    }
}
