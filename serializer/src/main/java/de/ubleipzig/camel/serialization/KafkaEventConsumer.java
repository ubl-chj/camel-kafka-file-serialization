/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.ubleipzig.camel.serialization;

import static de.ubleipzig.camel.serialization.ProcessorUtils.tokenizePropertyPlaceholder;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.camel.Exchange.CONTENT_TYPE;
import static org.apache.camel.Exchange.FILE_NAME;
import static org.apache.camel.Exchange.HTTP_METHOD;
import static org.apache.camel.Exchange.HTTP_PATH;
import static org.apache.camel.Exchange.HTTP_RESPONSE_CODE;
import static org.apache.camel.LoggingLevel.INFO;
import static org.apache.camel.builder.PredicateBuilder.and;
import static org.apache.camel.builder.PredicateBuilder.in;
import static org.apache.camel.builder.PredicateBuilder.or;
import static org.apache.camel.component.exec.ExecBinding.EXEC_COMMAND_ARGS;
import static org.slf4j.LoggerFactory.getLogger;
import static org.trellisldp.camel.ActivityStreamProcessor.ACTIVITY_STREAM_OBJECT_ID;
import static org.trellisldp.camel.ActivityStreamProcessor.ACTIVITY_STREAM_OBJECT_TYPE;
import static org.trellisldp.camel.ActivityStreamProcessor.ACTIVITY_STREAM_TYPE;

import java.io.InputStream;
import java.net.URI;

import org.apache.camel.RuntimeCamelException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.JndiRegistry;
import org.apache.camel.main.Main;
import org.apache.camel.main.MainListenerSupport;
import org.apache.camel.main.MainSupport;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.slf4j.Logger;
import org.trellisldp.camel.ActivityStreamProcessor;

/**
 * KafkaEventConsumer.
 *
 * @author christopher-johnson
 */
public class KafkaEventConsumer {

    private static final Logger LOGGER = getLogger(KafkaEventConsumer.class);
    private static final String IMAGE_OUTPUT = "CamelImageOutput";
    private static final String IMAGE_INPUT = "CamelImageInput";
    private static final String CREATE = "Create";
    private static final String UPDATE = "Update";
    private static final String CONVERT_OPTIONS = " -set colorspace sRGB -depth 8 -";

    /**
     * @param args String[]
     * @throws Exception Exception
     */
    public static void main(final String[] args) throws Exception {
        final KafkaEventConsumer kafkaConsumer = new KafkaEventConsumer();
        kafkaConsumer.init();
    }

    private void init() throws Exception {
        final Main main = new Main();
        main.addRouteBuilder(new KafkaEventRoute());
        main.addMainListener(new Events());
        final JndiRegistry registry = new JndiRegistry(ContextUtils.createInitialContext());
        main.bind("x509HostnameVerifier", new NoopHostnameVerifier());
        main.setPropertyPlaceholderLocations("file:${env:SERIALIZATION_HOME}/de.ubleipzig.camel.serialization.cfg");
        main.run();
    }

    public static class Events extends MainListenerSupport {

        @Override
        public void afterStart(final MainSupport main) {
            LOGGER.info("Camel Kafka Serialization is now started!");
        }

        @Override
        public void beforeStop(final MainSupport main) {
            LOGGER.info("Camel Kafka Serialization is now being stopped!");
        }
    }

    public static class KafkaEventRoute extends RouteBuilder {

        /**
         * Configure the event route.
         *
         * @throws Exception Exception.
         */
        public void configure() throws Exception {
            LOGGER.info("About to start route: Kafka Server -> Log ");

            from("kafka:{{consumer.topic}}?brokers={{kafka.host}}:{{kafka.port}}"
                    + "&maxPollRecords={{consumer.maxPollRecords}}"
                    + "&consumersCount={{consumer.consumersCount}}"
                    + "&seekTo={{consumer.seekTo}}"
                    + "&groupId={{consumer.group}}"
                    + "&autoCommitIntervalMs={{auto.commit.interval.ms}}")
                    .routeId("FromKafka")
                    .unmarshal()
                    .json(JsonLibrary.Jackson)
                    .process(new ActivityStreamProcessor())
                    .marshal()
                    .json(JsonLibrary.Jackson, true)
                    .log(INFO, LOGGER, "Marshalling ActivityStreamMessage to JSON-LD")
                    //.to("file://{{serialization.log}}");
                    .to("direct:get");

            from("direct:get")
                    .routeId("BinaryGet")
                    .choice()
                    .when(and(in(tokenizePropertyPlaceholder(getContext(), "{{indexable.types}}", ",")
                            .stream()
                            .map(type -> header(ACTIVITY_STREAM_OBJECT_TYPE).contains(type))
                            .collect(toList())), or(header(ACTIVITY_STREAM_TYPE).contains(CREATE),
                            header(ACTIVITY_STREAM_TYPE).contains(UPDATE))))
                        .setHeader(HTTP_METHOD)
                        .constant("GET")
                    .process(exchange -> {
                        final String resource = exchange
                                .getIn()
                                .getHeader(ACTIVITY_STREAM_OBJECT_ID, String.class);
                        final URI uri = new URI(resource);
                        final String path = uri.getPath();
                        exchange
                                .getIn()
                                .setHeader(HTTP_PATH, path);
                    })
                    .choice()
                        .when(header(ACTIVITY_STREAM_OBJECT_ID).contains("https"))
                            .to("https4://{{trellis.baseUrl}}?x509HostnameVerifier=#x509HostnameVerifier")
                            .when(header(ACTIVITY_STREAM_OBJECT_ID).contains("http"))
                            .to("http4://{{trellis.baseUrl}}")
                    .choice()
                    .when(header(CONTENT_TYPE).startsWith("image/"))
                        .log(INFO, LOGGER, "Image Processing ${headers[ActivityStreamObjectId]}")
                    .to("direct:convert")
                    .when(header("Link").contains("<http://www.w3.org/ns/ldp#NonRDFSource>;rel=\"type\""))
                        .setBody(constant("Error: this resource is not an image"))
                        .to("direct:invalidFormat")
                    .when(header(HTTP_RESPONSE_CODE).isEqualTo(200))
                        .setBody(constant("Error: this resource is not an ldp:NonRDFSource"))
                    .to("direct:invalidFormat")
                    .otherwise()
                    .to("direct:error");

            from("direct:invalidFormat")
                    .routeId("ImageInvalidFormat")
                    .removeHeaders("*")
                    .setHeader(CONTENT_TYPE).constant("text/plain")
                    .setHeader(HTTP_RESPONSE_CODE).constant(400);

            from("direct:error")
                    .routeId("ImageError")
                    .setBody(constant("Error: this resource is not accessible"))
                    .setHeader(CONTENT_TYPE).constant("text/plain");

            from("direct:convert")
                    .routeId("ImageConvert")
                    .setHeader(IMAGE_INPUT)
                    .header(CONTENT_TYPE)
                    .process(exchange -> {
                        final String accept = exchange
                                .getIn()
                                .getHeader(IMAGE_OUTPUT, "", String.class);
                        final String fmt = accept.matches("^image/\\w+$") ? accept.replace(
                                "image/", "") : getContext().resolvePropertyPlaceholders("{{default.output.format}}");
                        final boolean valid;
                        try {
                            valid = stream(getContext()
                                    .resolvePropertyPlaceholders("{{valid.formats}}")
                                    .split(",")).anyMatch(fmt::equals);
                        } catch (final Exception ex) {
                            throw new RuntimeCamelException("Couldn't resolve property placeholder", ex);
                        }

                        if (valid) {
                            exchange
                                    .getIn()
                                    .setHeader(IMAGE_OUTPUT, "image/" + fmt);
                            exchange
                                    .getIn()
                                    .setHeader(EXEC_COMMAND_ARGS, CONVERT_OPTIONS + " " + fmt + ":-");
                        } else {
                            throw new RuntimeCamelException("Invalid format: " + fmt);
                        }
                    })
                    .log(INFO, LOGGER,
                            "Converting from ${headers[CamelImageInput]} to " + "${headers[CamelImageOutput]}")
                    .to("exec:{{convert.path}}")
                    .log(INFO, LOGGER, "Converting Resource: ${headers[CamelHttpUri]}")
                    .process(exchange -> {
                        final String path = exchange
                                .getIn()
                                .getHeader(HTTP_PATH, String.class);
                        final String outpath = path.replace(
                                "tif", getContext().resolvePropertyPlaceholders("{{default.output.format}}"));
                        exchange
                                .getIn()
                                .setHeader(FILE_NAME, outpath);
                    })
                    .removeHeaders("CamelHttp*")
                    .to("direct:serialize");

            from("direct:serialize")
                    .process(exchange -> {
                        exchange
                                .getOut()
                                .setBody(exchange
                                        .getIn()
                                        .getBody(InputStream.class));
                        final String filename = exchange
                                .getIn()
                                .getHeader(FILE_NAME, String.class);
                        exchange
                                .getOut()
                                .setHeader(FILE_NAME, filename);
                    })
                    .log(INFO, LOGGER, "Filename: ${headers[CamelFileName]}")
                    .to("file://{{serialization.binaries}}");
        }
    }
}