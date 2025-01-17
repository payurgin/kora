package ru.tinkoff.kora.http.server.undertow;

import io.undertow.Undertow;
import io.undertow.server.handlers.GracefulShutdownHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.XnioWorker;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import ru.tinkoff.kora.application.graph.ValueOf;
import ru.tinkoff.kora.common.readiness.ReadinessProbe;
import ru.tinkoff.kora.common.readiness.ReadinessProbeFailure;
import ru.tinkoff.kora.common.util.ReactorUtils;
import ru.tinkoff.kora.http.server.common.HttpServer;
import ru.tinkoff.kora.http.server.common.HttpServerConfig;
import ru.tinkoff.kora.logging.common.arg.StructuredArgument;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class UndertowHttpServer implements HttpServer, ReadinessProbe {
    private static final Logger log = LoggerFactory.getLogger(UndertowHttpServer.class);

    private final AtomicReference<HttpServerState> state = new AtomicReference<>(HttpServerState.INIT);
    private final ValueOf<HttpServerConfig> config;
    private final ValueOf<UndertowPublicApiHandler> publicApiHandler;
    private final GracefulShutdownHandler gracefulShutdown;
    private final XnioWorker xnioWorker;
    private volatile Undertow undertow;

    public UndertowHttpServer(ValueOf<HttpServerConfig> config, ValueOf<UndertowPublicApiHandler> publicApiHandler, @Nullable XnioWorker xnioWorker) {
        this.config = config;
        this.publicApiHandler = publicApiHandler;
        this.xnioWorker = xnioWorker;
        this.gracefulShutdown = new GracefulShutdownHandler(exchange -> this.publicApiHandler.get().handleRequest(exchange));
    }

    @Override
    public Mono<Void> release() {
        return Mono.fromRunnable(() -> this.state.set(HttpServerState.SHUTDOWN))
            .then(Mono.delay(Duration.ofMillis(this.config.get().shutdownWait())))
            .then(ReactorUtils.ioMono(() -> {
                log.info("Stopping undertow");
                this.gracefulShutdown.shutdown();
                try {
                    this.gracefulShutdown.awaitShutdown();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                log.debug("Graceful shutdown awaited");
                if (this.undertow != null) {
                    this.undertow.stop();
                    this.undertow = null;
                }
                log.debug("Undertow stopped");
            }));
    }

    @Override
    public Mono<Void> init() {
        return Mono.create(sink -> {
            // dirty hack to start undertow thread as non daemon
            var t = new Thread(() -> {
                log.info("Starting undertow");
                try {
                    this.gracefulShutdown.start();
                    this.undertow = this.createServer();
                    this.undertow.start();
                    this.state.set(HttpServerState.RUN);
                    var data = StructuredArgument.marker(
                        "port", this.port()
                    );
                    log.info(data, "Undertow started");
                    sink.success();
                } catch (Throwable e) {
                    sink.error(e);
                }
            }, "undertow-init");
            t.setDaemon(false);
            t.start();
        });
    }

    private Undertow createServer() {
        return Undertow.builder()
            .addHttpListener(this.config.get().publicApiHttpPort(), "0.0.0.0", this.gracefulShutdown)
            .setWorker(this.xnioWorker)
            .build();
    }

    @Override
    public int port() {
        if (this.undertow == null) {
            return -1;
        }
        var infos = this.undertow.getListenerInfo();
        var address = (InetSocketAddress) infos.get(0).getAddress();
        return address.getPort();
    }

    @Override
    public Mono<ReadinessProbeFailure> probe() {
        return switch (this.state.get()) {
            case INIT -> Mono.just(new ReadinessProbeFailure("Public http server init"));
            case RUN -> Mono.empty();
            case SHUTDOWN -> Mono.just(new ReadinessProbeFailure("Public http server shutdown"));
        };
    }

    private enum HttpServerState {
        INIT, RUN, SHUTDOWN
    }
}
