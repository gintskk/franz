/*
 * Copyright 2021 Delft University of Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.c0ps.franz;

import static dev.c0ps.franz.Lane.ERROR;
import static dev.c0ps.franz.Lane.NORMAL;
import static dev.c0ps.franz.Lane.PRIORITY;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.c0ps.io.JsonUtils;
import dev.c0ps.io.TRef;

public class KafkaImpl implements Kafka, KafkaErrors {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaImpl.class);

    // Avoid special polling behavior with Duration.ZERO timeout, by using a small timeout
    private static final Duration POLL_TIMEOUT_ZEROISH = Duration.ofMillis(1);
    private static final Duration POLL_TIMEOUT_PRIO = Duration.ofSeconds(10);
    private static final Object NONE = new Object();

    private static final String EXT_NORM = "-" + Lane.NORMAL;
    private static final String EXT_PRIO = "-" + Lane.PRIORITY;
    private static final String EXT_ERR = "-" + Lane.ERROR;

    private final JsonUtils jsonUtils;
    private final boolean shouldAutoCommit;

    private final Map<Lane, KafkaConsumer<String, String>> conns = new HashMap<>();
    private final KafkaProducer<String, String> producer;

    private final Map<String, String> baseTopics = new HashMap<>();
    private final Map<String, Set<Callback<?>>> callbacks = new HashMap<>();

    private BackgroundHeartbeatHelper helper;
    private boolean hadMessages = true;

    @Inject
    public KafkaImpl(JsonUtils jsonUtils, KafkaConnector connector, @Named("KafkaImpl.shouldAutoCommit") boolean shouldAutoCommit) {
        this.jsonUtils = jsonUtils;
        this.shouldAutoCommit = shouldAutoCommit;
        for (var l : Lane.values()) {
            conns.put(l, connector.getConsumerConnection(l));
        }
        producer = connector.getProducerConnection();
    }

    @Override
    public void sendHeartbeat() {
        LOG.debug("Sending a heartbeat on all consumer connections ...");
        for (var conn : conns.values()) {
            sendHeartBeat(conn);
        }
    }

    public void setBackgroundHeartbeatHelper(BackgroundHeartbeatHelper helper) {
        if (this.helper != null) {
            this.helper.disable();
        }
        this.helper = helper;
    }

    private void assertBackgroundHelper() {
        if (helper == null) {
            throw new IllegalStateException("No BackgroundHeartbeatHelper configured in this KafkaImpl instance");
        }
    }

    @Override
    public void enableBackgroundHeartbeat() {
        assertBackgroundHelper();
        helper.enable();
    }

    @Override
    public void disableBackgroundHeartbeat() {
        assertBackgroundHelper();
        helper.disable();
    }

    @Override
    public void stop() {
        if (helper != null) {
            disableBackgroundHeartbeat();
        }
        for (var conn : conns.values()) {
            conn.wakeup();
            conn.close();
        }
        producer.close();
    }

    @Override
    public <T> void subscribe(String topic, Class<T> type, BiConsumer<T, Lane> callback) {
        subscribe(topic, type, callback, (x, y) -> NONE);
    }

    @Override
    public <T> void subscribe(String topic, Class<T> type, BiConsumer<T, Lane> callback, BiFunction<T, Throwable, ?> errors) {
        subscribe(topic, new Callback<T>(type, callback, errors));
    }

    @Override
    public <T> void subscribe(String topic, TRef<T> typeRef, BiConsumer<T, Lane> callback) {
        subscribe(topic, typeRef, callback, (x, y) -> NONE);
    }

    @Override
    public <T> void subscribe(String topic, TRef<T> typeRef, BiConsumer<T, Lane> callback, BiFunction<T, Throwable, ?> errors) {
        subscribe(topic, new Callback<T>(typeRef, callback, errors));
    }

    @Override
    public <T> void subscribeErrors(String topic, Class<T> type, Consumer<T> callback) {
        subscribeErrors(topic, new Callback<T>(type, (o, l) -> callback.accept(o), (x, y) -> NONE));
    }

    @Override
    public <T> void subscribeErrors(String topic, TRef<T> typeRef, Consumer<T> callback) {
        subscribeErrors(topic, new Callback<T>(typeRef, (o, l) -> callback.accept(o), (x, y) -> NONE));
    }

    private <T> void subscribe(String topic, Callback<T> cb) {
        subscribe(topic, cb, NORMAL);
        subscribe(topic, cb, PRIORITY);
    }

    private <T> void subscribeErrors(String topic, Callback<T> cb) {
        subscribe(topic, cb, ERROR);
    }

    private <T> void subscribe(String topic, Callback<T> cb, Lane l) {
        getCallbacks(topic, l).add(cb);

        var conn = conns.get(l);
        var subs = new HashSet<String>();
        subs.addAll(conn.subscription());
        subs.add(combine(topic, l));
        conn.subscribe(subs);

        LOG.debug("Subscribed ({}): {}", l, subs);
    }

    private Set<Callback<?>> getCallbacks(String baseTopic, Lane lane) {
        var combinedTopic = combine(baseTopic, lane);
        baseTopics.put(combinedTopic, baseTopic);

        Set<Callback<?>> vals;
        if (!callbacks.containsKey(combinedTopic)) {
            vals = new HashSet<Callback<?>>();
            callbacks.put(combinedTopic, vals);
        } else {
            vals = callbacks.get(combinedTopic);
        }
        return vals;
    }

    @Override
    public <T> void publish(T obj, String topic, Lane lane) {
        LOG.debug("Publishing to {} ({})", topic, lane);
        String json = jsonUtils.toJson(obj);
        var combinedTopic = combine(topic, lane);
        var record = new ProducerRecord<String, String>(combinedTopic, json);
        producer.send(record);
        producer.flush();
    }

    @Override
    public <T> void publish(String key, T obj, String topic, Lane lane) {
        LOG.debug("Publishing to {} ({})", topic, lane);
        String json = jsonUtils.toJson(obj);
        var combinedTopic = combine(topic, lane);
        var record = new ProducerRecord<String, String>(combinedTopic, key, json);
        producer.send(record);
        producer.flush();
    }

    @Override
    public synchronized void poll() {
        LOG.debug("Polling ...");
        // don't wait if any lane had messages, otherwise, only wait in PRIO
        var timeout = hadMessages ? POLL_TIMEOUT_ZEROISH : POLL_TIMEOUT_PRIO;
        var connPrio = conns.get(PRIORITY);
        var connNorm = conns.get(NORMAL);
        if (process(connPrio, PRIORITY, timeout)) {
            // make sure the session does not time out
            sendHeartBeat(connNorm);
        } else {
            process(connNorm, NORMAL, POLL_TIMEOUT_ZEROISH);
        }
    }

    @Override
    public synchronized void pollAllLanes() {
        boolean continuePolling;

        do {
            continuePolling = false;

            for (Lane lane : Lane.values()) {
                LOG.debug("Polling " + lane.name().toLowerCase() + "...");
                var conn = conns.get(lane);
                continuePolling |= process(conn, lane, POLL_TIMEOUT_PRIO);
            }
        } while (continuePolling);
    }

    @Override
    public synchronized void pollAllErrors() {
        LOG.debug("Polling errors ...");
        var c = conns.get(ERROR);

        while (c.assignment().isEmpty()) {
            // wait for assignment
            c.poll(POLL_TIMEOUT_ZEROISH);
        }
        c.seekToBeginning(c.assignment());
        while (process(c, Lane.ERROR, POLL_TIMEOUT_PRIO)) {
            // repeat
        }
    }

    @Override
    public synchronized void commit() {
        LOG.debug("Committing ...");
        for (var conn : conns.values()) {
            conn.commitSync();
        }
    }

    private boolean process(KafkaConsumer<String, String> con, Lane lane, Duration timeout) {
        LOG.debug("Processing record ...");
        hadMessages = false;
        try {
            for (var r : con.poll(timeout)) {
                LOG.debug("Received message on ('combined') topic {}, invoking callbacks ...", r.topic());
                hadMessages = true;
                var json = r.value();
                var cbs = callbacks.get(r.topic());
                for (var cb : cbs) {
                    cb.exec(r.topic(), json, lane);
                }
            }
            if (shouldAutoCommit) {
                con.commitSync();
            }
        } catch (WakeupException e) {
            // used by Kafka to interrupt long polls, can be ignored
        } catch (CommitFailedException e) {
            LOG.warn("Offset commit failed, stopping Kafka ...");
            stop();
            throw new KafkaException(e);
        }
        return hadMessages;
    }

    private String combine(String topic, Lane lane) {
        return new StringBuilder().append(topic).append(getSuffix(lane)).toString();
    }

    protected String getSuffix(Lane lane) {
        switch (lane) {
        case PRIORITY:
            return EXT_PRIO;
        case ERROR:
            return EXT_ERR;
        case NORMAL:
            return EXT_NORM;
        default:
            throw new IllegalStateException();
        }
    }

    private static synchronized void sendHeartBeat(KafkaConsumer<?, ?> c) {
        LOG.debug("Sending heartbeat ...");
        // See https://stackoverflow.com/a/43722731
        var partitions = c.assignment();
        if (partitions.size() == 0) {
            // ignoring heartbeats while not being assigned to a topic
            return;
        }
        c.pause(partitions);
        try {
            c.poll(POLL_TIMEOUT_ZEROISH);
        } catch (WakeupException e) {
            // used by Kafka to interrupt long polls, can be ignored
        }
        c.resume(partitions);
    }

    private class Callback<T> {

        private final Function<String, T> deserializer;
        private final BiConsumer<T, Lane> callback;
        private final BiFunction<T, Throwable, ?> errors;

        private Callback(Class<T> type, BiConsumer<T, Lane> callback, BiFunction<T, Throwable, ?> errors) {
            this.callback = callback;
            this.errors = errors;
            this.deserializer = json -> {
                return jsonUtils.fromJson(json, type);
            };
        }

        private Callback(TRef<T> typeRef, BiConsumer<T, Lane> callback, BiFunction<T, Throwable, ?> errors) {
            this.callback = callback;
            this.errors = errors;
            this.deserializer = json -> {
                return jsonUtils.fromJson(json, typeRef);
            };
        }

        public void exec(String combinedTopic, String json, Lane lane) {
            T obj = null;
            try {
                obj = deserializer.apply(json);
                callback.accept(obj, lane);
            } catch (Exception e) {
                var err = errors.apply(obj, e);
                // check instance equality!
                if (err == NONE) {
                    var msg = new StringBuilder("Unhandled exception when processing ").append(json).toString();
                    LOG.error(msg, e);
                } else {
                    var baseTopic = baseTopics.get(combinedTopic);
                    publish(err, baseTopic, ERROR);
                }
            }
        }
    }
}