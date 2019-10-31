package net.pincette.jes.http;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.valueOf;
import static java.lang.Integer.parseInt;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.parse;
import static java.util.logging.Logger.getGlobal;
import static java.util.logging.Logger.getLogger;
import static java.util.stream.Collectors.toMap;
import static javax.json.Json.createReader;
import static net.pincette.jes.elastic.Logging.log;
import static net.pincette.jes.util.Configuration.loadDefault;
import static net.pincette.jes.util.Kafka.fromConfig;
import static net.pincette.rs.Chain.with;
import static net.pincette.rs.Util.empty;
import static net.pincette.util.Collections.list;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.Util.tryToDoWithRethrow;
import static net.pincette.util.Util.tryToGetRethrow;
import static net.pincette.util.Util.tryToGetSilent;

import com.typesafe.config.Config;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.pincette.function.SideEffect;
import net.pincette.jes.api.Request;
import net.pincette.jes.api.Response;
import net.pincette.jes.api.Server;
import net.pincette.netty.http.HttpServer;
import net.pincette.rs.Util;
import net.pincette.util.Array;
import net.pincette.util.Json;
import org.reactivestreams.Publisher;

/**
 * A standalone HTTP server for JSON Event Sourcing.
 *
 * @author Werner Donn\u00e9
 * @since 1.0
 */
public class ApiServer {
  private static final String AUTHORIZATION_HEADER = "authorizationHeader";
  private static final String CONTEXT_PATH = "contextPath";
  private static final String ELASTIC_LOG = "elastic.log";
  private static final String ENVIRONMENT = "environment";
  private static final String FANOUT_SECRET = "fanout.secret";
  private static final String FANOUT_URI = "fanout.uri";
  private static final String JWT_PUBLIC_KEY = "jwtPublicKey";
  private static final String KAFKA = "kafka";
  private static final String LEVEL = "level";
  private static final String LOG_LEVEL = "logLevel";
  private static final String MONGODB_DATABASE = "mongodb.database";
  private static final String MONGODB_URI = "mongodb.uri";
  private static final String URI_FIELD = "uri";
  private static final String VERSION = "1.1.1";

  private static void copyHeaders(final Response r1, final HttpResponse r2) {
    if (r1.headers != null) {
      r1.headers.forEach((k, v) -> r2.headers().add(k, list(v)));
    }
  }

  private static CompletionStage<Publisher<ByteBuf>> health(
      final HttpRequest req, final HttpResponse resp) {
    resp.setStatus(OK);

    return completedFuture(empty());
  }

  private static boolean isHealthCheck(final HttpRequest req, final String contextPath) {
    return req.method().equals(GET) && isHealthCheckPath(req, contextPath);
  }

  private static boolean isHealthCheckPath(final HttpRequest req, final String contextPath) {
    return tryToGetRethrow(() -> new URI(req.uri()))
        .map(URI::getPath)
        .map(path -> path.equals(contextPath + "/health"))
        .orElse(false);
  }

  public static void main(final String[] args) {
    final Config config = loadDefault();
    final String contextPath = config.hasPath(CONTEXT_PATH) ? config.getString(CONTEXT_PATH) : "";
    final String environment = config.hasPath(ENVIRONMENT) ? config.getString(ENVIRONMENT) : "dev";
    final Level logLevel = parse(tryToGetSilent(() -> config.getString(LOG_LEVEL)).orElse("INFO"));
    final Logger logger = getLogger("pincette-jes-http");

    logger.setLevel(logLevel);
    tryToGetSilent(() -> config.getConfig(ELASTIC_LOG))
        .ifPresent(
            c ->
                log(
                    logger,
                    logLevel,
                    VERSION,
                    environment,
                    c.getString(URI_FIELD),
                    c.getString(AUTHORIZATION_HEADER)));

    tryToDoWithRethrow(
        () ->
            new Server()
                .withContextPath(contextPath)
                .withEnvironment(environment)
                .withAudit("audit-" + environment)
                .withBreakingTheGlass()
                .withJwtPublicKey(config.getString(JWT_PUBLIC_KEY))
                .withKafkaConfig(fromConfig(config, KAFKA))
                .withMongoUri(config.getString(MONGODB_URI))
                .withMongoDatabase(config.getString(MONGODB_DATABASE))
                .withFanoutUri(config.getString(FANOUT_URI))
                .withFanoutSecret(config.getString(FANOUT_SECRET))
                .withLogger(logger),
        server ->
            tryToDoWithRethrow(
                () ->
                    new HttpServer(
                        parseInt(args[0]),
                        (HttpRequest req, InputStream body, HttpResponse resp) ->
                            isHealthCheck(req, contextPath)
                                ? health(req, resp)
                                : requestHandler(req, body, resp, server)),
                ApiServer::start));
  }

  private static CompletionStage<Publisher<ByteBuf>> requestHandler(
      final HttpRequest request,
      final InputStream body,
      final HttpResponse response,
      final Server server) {
    return toRequest(request)
        .map(r -> r.withBody(tryToGetSilent(() -> createReader(body).read()).orElse(null)))
        .map(r -> pair(server.returnsMultiple(r), server.request(r)))
        .map(
            pair ->
                pair.second
                    .thenApply(r -> toResult(r, response, pair.first).orElseGet(Util::empty))
                    .exceptionally(
                        t ->
                            SideEffect.<Publisher<ByteBuf>>run(
                                    () -> {
                                      getGlobal().log(SEVERE, "", t);
                                      response.setStatus(INTERNAL_SERVER_ERROR);
                                    })
                                .andThenGet(Util::empty)))
        .orElseGet(
            () ->
                SideEffect.<CompletionStage<Publisher<ByteBuf>>>run(
                        () -> response.setStatus(BAD_REQUEST))
                    .andThenGet(() -> completedFuture(empty())));
  }

  private static void start(final HttpServer server) {
    getGlobal().info("Ready");
    server.start();
    getGlobal().info("Done");
  }

  private static Map<String, String[]> toHeaders(final HttpHeaders headers) {
    return headers.entries().stream()
        .collect(toMap(Map.Entry::getKey, e -> new String[] {e.getValue()}, Array::append));
  }

  private static Optional<Request> toRequest(final HttpRequest request) {
    return tryToGetRethrow(() -> new URI(request.uri()))
        .map(
            uri ->
                new Request()
                    .withMethod(request.method().name())
                    .withHeaders(toHeaders(request.headers()))
                    .withPath(uri.getPath())
                    .withQueryString(uri.getQuery()));
  }

  private static Optional<Publisher<ByteBuf>> toResult(
      final Response r1, final HttpResponse r2, final boolean returnsMultiple) {
    r2.setStatus(valueOf(r1.statusCode));
    copyHeaders(r1, r2);

    if (r1.body != null) {
      r2.headers().set("Content-Type", "application/json");
    }

    return Optional.ofNullable(r1.body)
        .map(body -> with(body).map(Json::string))
        .map(chain -> returnsMultiple ? chain.separate(",").before("[").after("]") : chain)
        .map(chain -> chain.map(s -> s.getBytes(UTF_8)).map(Unpooled::wrappedBuffer).get());
  }
}
