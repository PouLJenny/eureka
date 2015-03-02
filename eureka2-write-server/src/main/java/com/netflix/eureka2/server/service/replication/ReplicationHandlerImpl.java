package com.netflix.eureka2.server.service.replication;

import com.netflix.eureka2.channel.ChannelFactory;
import com.netflix.eureka2.channel.ReplicationChannel;
import com.netflix.eureka2.connection.RetryableConnection;
import com.netflix.eureka2.connection.RetryableConnectionFactory;
import com.netflix.eureka2.interests.ChangeNotification;
import com.netflix.eureka2.interests.Interests;
import com.netflix.eureka2.metric.server.WriteServerMetricFactory;
import com.netflix.eureka2.protocol.replication.ReplicationHello;
import com.netflix.eureka2.protocol.replication.ReplicationHelloReply;
import com.netflix.eureka2.registry.Source;
import com.netflix.eureka2.registry.SourcedEurekaRegistry;
import com.netflix.eureka2.registry.instance.InstanceInfo;
import com.netflix.eureka2.server.channel.SenderReplicationChannelFactory;
import com.netflix.eureka2.server.config.WriteServerConfig;
import com.netflix.eureka2.Server;
import com.netflix.eureka2.utils.rx.RetryStrategyFunc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author David Liu
 */
public class ReplicationHandlerImpl implements ReplicationHandler {

    enum STATE {Idle, Replicating, Closed}

    private static final Logger logger = LoggerFactory.getLogger(ReplicationHandlerImpl.class);

    private static final int DEFAULT_RETRY_WAIT_MILLIS = 500;

    private final ChannelFactory<ReplicationChannel> channelFactory;
    private final int retryWaitMillis;
    private final RetryableConnection<ReplicationChannel> connection;
    private final Subscriber<Void> replicationSubscriber;
    private final AtomicReference<STATE> stateRef;

    public ReplicationHandlerImpl(final WriteServerConfig config,
                                  final Server address,
                                  final SourcedEurekaRegistry<InstanceInfo> registry,
                                  final InstanceInfo selfInfo,
                                  final WriteServerMetricFactory metricFactory) {
        this(new SenderReplicationChannelFactory(config, address, metricFactory), DEFAULT_RETRY_WAIT_MILLIS, registry, selfInfo);
    }

    /*visible for testing*/ ReplicationHandlerImpl(
                                  final ChannelFactory<ReplicationChannel> channelFactory,
                                  final int retryWaitMillis,
                                  final SourcedEurekaRegistry<InstanceInfo> registry,
                                  final InstanceInfo selfInfo) {
        this.stateRef = new AtomicReference<>(STATE.Idle);
        this.retryWaitMillis = retryWaitMillis;
        this.channelFactory = channelFactory;

        final String ownInstanceId = selfInfo.getId();

        final RetryableConnectionFactory<ReplicationChannel> connectionFactory =
                new RetryableConnectionFactory<>(channelFactory);

        connection = connectionFactory.zeroOpConnection(new Func1<ReplicationChannel, Observable<Void>>() {
            @Override
            public Observable<Void> call(final ReplicationChannel replicationChannel) {
                return replicationChannel.hello(new ReplicationHello(ownInstanceId, registry.size()))
                        .take(1)
                        .map(new Func1<ReplicationHelloReply, ReplicationChannel>() {
                            @Override
                            public ReplicationChannel call(ReplicationHelloReply replicationHelloReply) {
                                if (replicationHelloReply.getSourceId().equals(ownInstanceId)) {
                                    logger.info("{}: Taking out replication connection to itself", ownInstanceId);
                                    replicationChannel.close();  // gracefully close
                                    return null;
                                } else {
                                    logger.info("{} received hello back from {}", ownInstanceId, replicationHelloReply.getSourceId());
                                    return replicationChannel;
                                }
                            }
                        })
                        .filter(new Func1<ReplicationChannel, Boolean>() {
                            @Override
                            public Boolean call(ReplicationChannel channel) {
                                return channel != null;
                            }
                        })
                        .flatMap(new ReplicateFunc(registry));
            }
        });

        this.replicationSubscriber = new Subscriber<Void>() {
            @Override
            public void onCompleted() {
                logger.info("sender replication connection onCompleted");
            }

            @Override
            public void onError(Throwable e) {
                logger.warn("sender replication connection onError", e);
            }

            @Override
            public void onNext(Void aVoid) {

            }
        };
    }

    @Override
    public void startReplication() {
        if (stateRef.compareAndSet(STATE.Idle, STATE.Replicating)) {
            // TODO better retry func?
            connection.getRetryableLifecycle()
                    .retryWhen(new RetryStrategyFunc(retryWaitMillis))
                    .subscribe(replicationSubscriber);
        }
    }

    @Override
    public void shutdown() {
        STATE prev = stateRef.getAndSet(STATE.Closed);
        if (prev == STATE.Replicating) {
            replicationSubscriber.unsubscribe();
            connection.close();
            channelFactory.shutdown();
        }
    }


    protected static class ReplicateFunc implements Func1<ReplicationChannel, Observable<Void>> {
        private final SourcedEurekaRegistry<InstanceInfo> registry;

        public ReplicateFunc(SourcedEurekaRegistry<InstanceInfo> registry) {
            this.registry = registry;
        }

        @Override
        public Observable<Void> call(final ReplicationChannel channel) {
            return registry.forInterest(Interests.forFullRegistry(), Source.matcherFor(Source.Origin.LOCAL))
                    .flatMap(new Func1<ChangeNotification<InstanceInfo>, Observable<Void>>() {
                        @Override
                        public Observable<Void> call(ChangeNotification<InstanceInfo> notification) {
                            switch (notification.getKind()) {
                                case Add:
                                    return channel.register(notification.getData());
                                case Modify:
                                    return channel.register(notification.getData());
                                case Delete:
                                    return channel.unregister(notification.getData().getId());
                                default:
                                    logger.warn("Unrecognised notification kind {}", notification);
                                    return Observable.empty();
                            }

                        }
                    });
        }
    }
}
