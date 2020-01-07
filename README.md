# HTTP Server For JSON Event Sourcing

This is a standalone HTTP server for interacting with [JSON
aggregates](https://github.com/json-event-sourcing/pincette-jes). On the write-side you can send
commands, which are handled asynchronously. They are put on a [Kafka](https://kafka.apache.org)
command topic, which corresponds to the aggregate type in the [command](https://github.com/json-event-sourcing/pincette-jes). This is acknowledged with a 202 HTTP status code (Accepted). Changes to aggregates come back through [Server-Sent Events](https://www.w3.org/TR/eventsource/). This flow fits well with reactive clients.

The read-side is handled with [MongoDB](https://www.mongodb.com). You can fetch, list and search aggregates.

The supported paths and methods are explained in the repository [pincette-jes-api](https://github.com/json-event-sourcing/pincette-jes-api).

One special path is ```<contextPath>/health```, which just returns status code 200 (OK). This can be used for health checks.

## Authentication

All requests should have a [JSON Web Token](https://jwt.io), which may appear as a bearer token in the ```Authotrization``` header, the cookie named ```access_token``` or the URL parameter named ```access_token```. The configuration should have the public key with which the tokens can be validated.

## Configuration

The configuration is managed by the [Lightbend Config package](https://github.com/lightbend/config). By default it will try to load ```conf/application.conf```. An alternative configuration may be loaded by adding ```-Dconfig.resource=myconfig.conf```, where the file is also supposed to be in the ```conf``` directory. The following entries are available.

|Entry|Description|
|---|---|
|contextPath|The URL path prefix, e.g. "/api".|
|elastic.log.authorizationHeader|The value for the HTTP Authorization header, which uses the basic realm.|
|elastic.log.uri|The URI for upload of an Elasticsearch index. Its path would be ```/<index_name>/_doc```. The index "log" is currently used.|
|environment|The name of the environment, which will be used as a suffix for the aggregates, e.g. "tst", "acc", etc.|
|fanout.uri|The URL of the [fanout.io](https://fanout.io) service.|
|fanout.secret|The secret with which the usernames are encrypted during the Server-Sent Events set-up.|
|jwtPublicKey|The public key string, which is used to validate all JSON Web Tokens.|
|kafka|All Kafka settings come below this entry. So for example, the setting ```bootstrap.servers``` would go to the entry ```kafka.bootstrap.servers```.|
|logLevel|The log level as defined in [java.util.logging.Level](https://docs.oracle.com/javase/8/docs/api/java/util/logging/Level.html).|
|mongodb.uri|The URI of the MongoDB service.|
|mongodb.database|The name of the MongoDB database.|

## Building and Running

You can build the tool with ```mvn clean package```. This will produce a self-contained JAR-file in the ```target``` directory with the form ```pincette-jes-http-<version>-jar-with-dependencies.jar```. You can launch this JAR with ```java -jar```, followed by a port number.

You can run the JVM with the option ```-mx128m```.
