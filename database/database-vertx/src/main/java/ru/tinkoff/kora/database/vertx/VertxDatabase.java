package ru.tinkoff.kora.database.vertx;

import io.netty.channel.EventLoopGroup;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import reactor.core.publisher.Mono;
import ru.tinkoff.kora.application.graph.Lifecycle;
import ru.tinkoff.kora.application.graph.Wrapped;
import ru.tinkoff.kora.common.Context;
import ru.tinkoff.kora.database.common.telemetry.DataBaseTelemetry;
import ru.tinkoff.kora.database.common.telemetry.DataBaseTelemetryFactory;
import ru.tinkoff.kora.database.vertx.pool.VertxSqlPool;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class VertxDatabase implements Lifecycle, Wrapped<Pool>, VertxConnectionFactory {
    private final Context.Key<SqlConnection> connectionKey = new Context.Key<>() {
        @Override
        protected SqlConnection copy(SqlConnection object) {
            return null;
        }
    };
    private final Context.Key<Transaction> transactionKey = new Context.Key<>() {
        @Override
        protected Transaction copy(Transaction object) {
            return null;
        }
    };
    private final Pool pool;
    private final VertxDatabaseConfig vertxDatabaseConfig;
    private final EventLoopGroup eventLoopGroup;
    private final DataBaseTelemetry telemetry;

    public VertxDatabase(VertxDatabaseConfig vertxDatabaseConfig, EventLoopGroup eventLoopGroup, DataBaseTelemetryFactory telemetryFactory) {
        this.vertxDatabaseConfig = vertxDatabaseConfig;
        this.telemetry = telemetryFactory.get(vertxDatabaseConfig.poolName(), "postgres", vertxDatabaseConfig.username());
        this.pool = new VertxSqlPool(vertxDatabaseConfig, eventLoopGroup);
        this.eventLoopGroup = eventLoopGroup;
    }

    @Override
    public Mono<SqlConnection> currentConnection() {
        return Mono.deferContextual(reactorContext -> {
            var ctx = Context.Reactor.current(reactorContext);
            var connection = ctx.get(this.connectionKey);
            if (connection == null) {
                return Mono.empty();
            } else {
                return Mono.just(connection);
            }
        });
    }

    @Override
    public Mono<SqlConnection> newConnection() {
        return Mono.create(sink -> {
            this.pool.getConnection(result -> {
                if (result.succeeded()) {
                    sink.success(result.result());
                } else {
                    sink.error(result.cause());
                }
            });
        });
    }

    @Override
    public DataBaseTelemetry telemetry() {
        return this.telemetry;
    }

    @Override
    public <T> Mono<T> withConnection(Function<SqlConnection, Mono<T>> callback) {
        return Mono.deferContextual(reactorContext -> {
            var ctx = Context.Reactor.current(reactorContext);
            var currentConnection = ctx.get(this.connectionKey);
            if (currentConnection != null) {
                return callback.apply(currentConnection);
            }
            return Mono.<T>create(sink -> this.pool.withConnection(connection -> {
                    ctx.set(this.connectionKey, connection);

                    var complete = new AtomicBoolean();

                    return Future.<T>future(promise -> callback.apply(connection).subscribe(
                        v -> {
                            if (complete.compareAndSet(false, true)) {
                                promise.complete(v);
                            }
                        },
                        promise::fail,
                        () -> {
                            if (complete.compareAndSet(false, true)) {
                                promise.complete();
                            }
                        },
                        Context.Reactor.inject(reactorContext, ctx)
                    ));
                }, asyncResult -> {
                    if (asyncResult.failed()) {
                        sink.error(asyncResult.cause());
                    } else {
                        sink.success(asyncResult.result());
                    }
                    ctx.remove(this.connectionKey);
                }))
                .contextWrite(c -> Context.Reactor.inject(c, ctx));
        });
    }

    @Override
    public <T> Mono<T> inTx(Function<SqlConnection, Mono<T>> callback) {
        return this.withConnection(connection -> Mono.deferContextual(reactorContext -> {
            var ctx = Context.Reactor.current(reactorContext);
            var currentTransaction = ctx.get(this.transactionKey);
            if (currentTransaction != null) {
                return callback.apply(connection);
            }
            return Mono.create(sink -> connection.begin(txEvent -> {
                if (txEvent.failed()) {
                    sink.error(txEvent.cause());
                    return;
                }
                var tx = txEvent.result();
                ctx.set(this.transactionKey, tx);
                var completed = new AtomicBoolean(false);
                callback.apply(connection).subscribe(
                    result -> {},
                    error -> {
                        if (completed.compareAndSet(false, true)) {
                            tx.rollback(v -> {
                                var oldCtx = Context.current();
                                try {
                                    ctx.inject();
                                    if (v.failed()) {
                                        error.addSuppressed(v.cause());
                                    }
                                    sink.error(error);
                                } finally {
                                    ctx.remove(this.transactionKey);
                                    oldCtx.inject();
                                }
                            });
                        }
                    },
                    () -> {
                        if (completed.compareAndSet(false, true)) {
                            tx.commit(v -> {
                                var oldCtx1 = Context.current();
                                try {
                                    ctx.inject();
                                    if (v.succeeded()) {
                                        sink.success();
                                    } else {
                                        sink.error(v.cause());
                                    }
                                } finally {
                                    ctx.remove(this.transactionKey);
                                    oldCtx1.inject();
                                }
                            });
                        } else {
                            ctx.remove(this.transactionKey);
                        }
                    },
                    Context.Reactor.inject(reactorContext, ctx)
                );
            }));
        }));
    }

    @Override
    public Mono<Void> init() {
        return Mono.create(sink -> this.pool.query("SELECT 1").execute(result -> {
            if (result.succeeded()) {
                sink.success();
            } else {
                sink.error(result.cause());
            }
        }));
    }

    @Override
    public Mono<Void> release() {
        return Mono.create(sink -> sink.onRequest(l -> this.pool.close(event -> {
            if (event.succeeded()) {
                sink.success();
            } else {
                sink.error(event.cause());
            }
        })));
    }

    @Override
    public Pool value() {
        return this.pool;
    }
}
