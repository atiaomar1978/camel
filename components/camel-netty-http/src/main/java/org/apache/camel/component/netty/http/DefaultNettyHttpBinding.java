/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.netty.http;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.NoTypeConversionAvailableException;
import org.apache.camel.TypeConverter;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.MessageHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.URISupport;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link NettyHttpBinding}.
 */
public class DefaultNettyHttpBinding implements NettyHttpBinding {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultNettyHttpBinding.class);
    private HeaderFilterStrategy headerFilterStrategy;

    public DefaultNettyHttpBinding() {
    }

    public DefaultNettyHttpBinding(HeaderFilterStrategy headerFilterStrategy) {
        this.headerFilterStrategy = headerFilterStrategy;
    }

    @Override
    public Message toCamelMessage(HttpRequest request, Exchange exchange, NettyHttpConfiguration configuration) throws Exception {
        LOG.trace("toCamelMessage: {}", request);

        NettyHttpMessage answer = new NettyHttpMessage(request, null);
        answer.setExchange(exchange);
        if (configuration.isMapHeaders()) {
            populateCamelHeaders(request, answer.getHeaders(), exchange, configuration);
        }

        if (configuration.isDisableStreamCache()) {
            // keep the body as is, and use type converters
            answer.setBody(request.getContent());
        } else {
            // turn the body into stream cached
            NettyChannelBufferStreamCache cache = new NettyChannelBufferStreamCache(request.getContent());
            answer.setBody(cache);
        }
        return answer;
    }

    @Override
    public void populateCamelHeaders(HttpRequest request, Map<String, Object> headers, Exchange exchange, NettyHttpConfiguration configuration) throws Exception {
        LOG.trace("populateCamelHeaders: {}", request);

        // NOTE: these headers is applied using the same logic as camel-http/camel-jetty to be consistent

        headers.put(Exchange.HTTP_METHOD, request.getMethod().getName());
        // strip query parameters from the uri
        String s = request.getUri();
        if (s.contains("?")) {
            s = ObjectHelper.before(s, "?");
        }

        // we want the full path for the url, as the client may provide the url in the HTTP headers as absolute or relative, eg
        //   /foo
        //   http://servername/foo
        String http = configuration.isSsl() ? "https://" : "http://";
        if (!s.startsWith(http)) {
            if (configuration.getPort() != 80) {
                s = http + configuration.getHost() + ":" + configuration.getPort() + s;
            } else {
                s = http + configuration.getHost() + s;
            }
        }

        headers.put(Exchange.HTTP_URL, s);
        // uri is without the host and port
        URI uri = new URI(request.getUri());
        // uri is path and query parameters
        headers.put(Exchange.HTTP_URI, uri.getPath());
        headers.put(Exchange.HTTP_QUERY, uri.getQuery());

        // strip the starting endpoint path so the path is relative to the endpoint uri
        String path = uri.getPath();
        if (configuration.getPath() != null && path.startsWith(configuration.getPath())) {
            path = path.substring(configuration.getPath().length());
        }
        headers.put(Exchange.HTTP_PATH, path);

        if (LOG.isTraceEnabled()) {
            LOG.trace("HTTP-Method {}", request.getMethod().getName());
            LOG.trace("HTTP-Uri {}", request.getUri());
        }

        for (String name : request.getHeaderNames()) {
            // mapping the content-type
            if (name.toLowerCase(Locale.US).equals("content-type")) {
                name = Exchange.CONTENT_TYPE;
            }

            if (name.toLowerCase(Locale.US).equals("authorization")) {
                String value = request.getHeader(name);
                // store a special header that this request was authenticated using HTTP Basic
                if (value != null && value.trim().startsWith("Basic")) {
                    if (headerFilterStrategy != null
                            && !headerFilterStrategy.applyFilterToExternalHeaders(NettyHttpConstants.HTTP_AUTHENTICATION, "Basic", exchange)) {
                        NettyHttpHelper.appendHeader(headers, NettyHttpConstants.HTTP_AUTHENTICATION, "Basic");
                    }
                }
            }

            // add the headers one by one, and use the header filter strategy
            List<String> values = request.getHeaders(name);
            Iterator<?> it = ObjectHelper.createIterator(values);
            while (it.hasNext()) {
                Object extracted = it.next();
                Object decoded = configuration.isUrlDecodeHeaders() ? URLDecoder.decode(extracted.toString(), "UTF-8") : extracted.toString();
                LOG.trace("HTTP-header: {}", extracted);
                if (headerFilterStrategy != null
                        && !headerFilterStrategy.applyFilterToExternalHeaders(name, decoded, exchange)) {
                    NettyHttpHelper.appendHeader(headers, name, decoded);
                }
            }
        }

        // add uri parameters as headers to the Camel message
        if (request.getUri().contains("?")) {
            String query = ObjectHelper.after(request.getUri(), "?");
            Map<String, Object> uriParameters = URISupport.parseQuery(query);

            for (Map.Entry<String, Object> entry : uriParameters.entrySet()) {
                String name = entry.getKey();
                Object values = entry.getValue();
                Iterator<?> it = ObjectHelper.createIterator(values);
                while (it.hasNext()) {
                    Object extracted = it.next();
                    Object decoded = configuration.isUrlDecodeHeaders() ? URLDecoder.decode(extracted.toString(), "UTF-8") : extracted.toString();
                    LOG.trace("URI-Parameter: {}", extracted);
                    if (headerFilterStrategy != null
                            && !headerFilterStrategy.applyFilterToExternalHeaders(name, decoded, exchange)) {
                        NettyHttpHelper.appendHeader(headers, name, decoded);
                    }
                }
            }
        }

        // if body is application/x-www-form-urlencoded then extract the body as query string and append as headers
        if (request.getMethod().getName().equals("POST") && request.getHeader(Exchange.CONTENT_TYPE) != null
                && request.getHeader(Exchange.CONTENT_TYPE).startsWith(NettyHttpConstants.CONTENT_TYPE_WWW_FORM_URLENCODED)) {

            String charset = "UTF-8";

            // Push POST form params into the headers to retain compatibility with DefaultHttpBinding
            String body = request.getContent().toString(Charset.forName(charset));
            if (ObjectHelper.isNotEmpty(body)) {
                for (String param : body.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2) {
                        String name = configuration.isUrlDecodeHeaders() ? URLDecoder.decode(pair[0], charset) : pair[0];
                        String value = configuration.isUrlDecodeHeaders() ? URLDecoder.decode(pair[1], charset) : pair[1];
                        if (headerFilterStrategy != null
                                && !headerFilterStrategy.applyFilterToExternalHeaders(name, value, exchange)) {
                            NettyHttpHelper.appendHeader(headers, name, value);
                        }
                    } else {
                        throw new IllegalArgumentException("Invalid parameter, expected to be a pair but was " + param);
                    }
                }
            }
        }

    }

    @Override
    public Message toCamelMessage(HttpResponse response, Exchange exchange, NettyHttpConfiguration configuration) throws Exception {
        LOG.trace("toCamelMessage: {}", response);

        NettyHttpMessage answer = new NettyHttpMessage(null, response);
        answer.setExchange(exchange);
        if (configuration.isMapHeaders()) {
            populateCamelHeaders(response, answer.getHeaders(), exchange, configuration);
        }

        // keep the body as is, and use type converters
        answer.setBody(response.getContent());
        return answer;
    }

    @Override
    public void populateCamelHeaders(HttpResponse response, Map<String, Object> headers, Exchange exchange, NettyHttpConfiguration configuration) throws Exception {
        LOG.trace("populateCamelHeaders: {}", response);

        headers.put(Exchange.HTTP_RESPONSE_CODE, response.getStatus().getCode());
        headers.put(NettyHttpConstants.HTTP_RESPONSE_TEXT, response.getStatus().getReasonPhrase());

        for (String name : response.getHeaderNames()) {
            // mapping the content-type
            if (name.toLowerCase().equals("content-type")) {
                name = Exchange.CONTENT_TYPE;
            }
            // add the headers one by one, and use the header filter strategy
            List<String> values = response.getHeaders(name);
            Iterator<?> it = ObjectHelper.createIterator(values);
            while (it.hasNext()) {
                Object extracted = it.next();
                LOG.trace("HTTP-header: {}", extracted);
                if (headerFilterStrategy != null
                        && !headerFilterStrategy.applyFilterToExternalHeaders(name, extracted, exchange)) {
                    NettyHttpHelper.appendHeader(headers, name, extracted);
                }
            }
        }
    }

    @Override
    public HttpResponse toNettyResponse(Message message, NettyHttpConfiguration configuration) throws Exception {
        LOG.trace("toNettyResponse: {}", message);

        // the message body may already be a Netty HTTP response
        if (message.getBody() instanceof HttpResponse) {
            return (HttpResponse) message.getBody();
        }

        // the response code is 200 for OK and 500 for failed
        boolean failed = message.getExchange().isFailed();
        int defaultCode = failed ? 500 : 200;

        int code = message.getHeader(Exchange.HTTP_RESPONSE_CODE, defaultCode, int.class);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code));
        LOG.trace("HTTP Status Code: {}", code);

        TypeConverter tc = message.getExchange().getContext().getTypeConverter();

        // append headers
        // must use entrySet to ensure case of keys is preserved
        for (Map.Entry<String, Object> entry : message.getHeaders().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            // use an iterator as there can be multiple values. (must not use a delimiter)
            final Iterator<?> it = ObjectHelper.createIterator(value, null);
            while (it.hasNext()) {
                String headerValue = tc.convertTo(String.class, it.next());
                if (headerValue != null && headerFilterStrategy != null
                        && !headerFilterStrategy.applyFilterToCamelHeaders(key, headerValue, message.getExchange())) {
                    LOG.trace("HTTP-Header: {}={}", key, headerValue);
                    response.addHeader(key, headerValue);
                }
            }
        }

        Object body = message.getBody();
        Exception cause = message.getExchange().getException();
        // support bodies as native Netty
        ChannelBuffer buffer;

        // if there was an exception then use that as body
        if (cause != null) {
            if (configuration.isTransferException()) {
                // we failed due an exception, and transfer it as java serialized object
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos);
                oos.writeObject(cause);
                oos.flush();
                IOHelper.close(oos, bos);

                // the body should be the serialized java object of the exception
                body = ChannelBuffers.copiedBuffer(bos.toByteArray());
                // force content type to be serialized java object
                message.setHeader(Exchange.CONTENT_TYPE, NettyHttpConstants.CONTENT_TYPE_JAVA_SERIALIZED_OBJECT);
            } else {
                // we failed due an exception so print it as plain text
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                cause.printStackTrace(pw);

                // the body should then be the stacktrace
                body = ChannelBuffers.copiedBuffer(sw.toString().getBytes());
                // force content type to be text/plain as that is what the stacktrace is
                message.setHeader(Exchange.CONTENT_TYPE, "text/plain");
            }

            // and mark the exception as failure handled, as we handled it by returning it as the response
            ExchangeHelper.setFailureHandled(message.getExchange());
        }

        if (body instanceof ChannelBuffer) {
            buffer = (ChannelBuffer) body;
        } else {
            // try to convert to buffer first
            buffer = message.getBody(ChannelBuffer.class);
            if (buffer == null) {
                // fallback to byte array as last resort
                byte[] data = message.getBody(byte[].class);
                if (data != null) {
                    buffer = ChannelBuffers.copiedBuffer(data);
                } else {
                    // and if byte array fails then try String
                    String str;
                    if (body != null) {
                        str = message.getMandatoryBody(String.class);
                    } else {
                        str = "";
                    }
                    buffer = ChannelBuffers.copiedBuffer(str.getBytes());
                }
            }
        }
        if (buffer != null) {
            response.setContent(buffer);
            int len = buffer.readableBytes();
            // set content-length
            response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, len);
            LOG.trace("Content-Length: {}", len);
        }

        // set the content type in the response.
        String contentType = MessageHelper.getContentType(message);
        if (contentType != null) {
            // set content-type
            response.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType);
            LOG.trace("Content-Type: {}", contentType);
        }

        // configure connection to accordingly to keep alive configuration
        // favor using the header from the message
        String connection = message.getHeader(HttpHeaders.Names.CONNECTION, String.class);
        if (connection == null) {
            // fallback and use the keep alive from the configuration
            if (configuration.isKeepAlive()) {
                connection = HttpHeaders.Values.KEEP_ALIVE;
            } else {
                connection = HttpHeaders.Values.CLOSE;
            }
        }
        response.setHeader(HttpHeaders.Names.CONNECTION, connection);
        LOG.trace("Connection: {}", connection);

        return response;
    }

    @Override
    public HttpRequest toNettyRequest(Message message, String uri, NettyHttpConfiguration configuration) throws Exception {
        LOG.trace("toNettyRequest: {}", message);

        // the message body may already be a Netty HTTP response
        if (message.getBody() instanceof HttpRequest) {
            return (HttpRequest) message.getBody();
        }

        // just assume GET for now, we will later change that to the actual method to use
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);

        TypeConverter tc = message.getExchange().getContext().getTypeConverter();

        // if we bridge endpoint then we need to skip matching headers with the HTTP_QUERY to avoid sending
        // duplicated headers to the receiver, so use this skipRequestHeaders as the list of headers to skip
        Map<String, Object> skipRequestHeaders = null;
        if (configuration.isBridgeEndpoint()) {
            String queryString = message.getHeader(Exchange.HTTP_QUERY, String.class);
            if (queryString != null) {
                skipRequestHeaders = URISupport.parseQuery(queryString);
            }
            // Need to remove the Host key as it should be not used
            message.getHeaders().remove("host");
        }

        // append headers
        // must use entrySet to ensure case of keys is preserved
        for (Map.Entry<String, Object> entry : message.getHeaders().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // we should not add headers for the parameters in the uri if we bridge the endpoint
            // as then we would duplicate headers on both the endpoint uri, and in HTTP headers as well
            if (skipRequestHeaders != null && skipRequestHeaders.containsKey(key)) {
                continue;
            }

            // use an iterator as there can be multiple values. (must not use a delimiter)
            final Iterator<?> it = ObjectHelper.createIterator(value, null, true);
            while (it.hasNext()) {
                String headerValue = tc.convertTo(String.class, it.next());

                if (headerValue != null && headerFilterStrategy != null
                        && !headerFilterStrategy.applyFilterToCamelHeaders(key, headerValue, message.getExchange())) {
                    LOG.trace("HTTP-Header: {}={}", key, headerValue);
                    request.addHeader(key, headerValue);
                }
            }
        }

        Object body = message.getBody();
        if (body != null) {
            // support bodies as native Netty
            ChannelBuffer buffer;
            if (body instanceof ChannelBuffer) {
                buffer = (ChannelBuffer) body;
            } else {
                // try to convert to buffer first
                buffer = message.getBody(ChannelBuffer.class);
                if (buffer == null) {
                    // fallback to byte array as last resort
                    byte[] data = message.getMandatoryBody(byte[].class);
                    buffer = ChannelBuffers.copiedBuffer(data);
                }
            }
            if (buffer != null) {
                request.setContent(buffer);
                int len = buffer.readableBytes();
                // set content-length
                request.setHeader(HttpHeaders.Names.CONTENT_LENGTH, len);
                LOG.trace("Content-Length: {}", len);
            } else {
                // we do not support this kind of body
                throw new NoTypeConversionAvailableException(body, ChannelBuffer.class);
            }
        }

        // update HTTP method accordingly as we know if we have a body or not
        HttpMethod method = NettyHttpHelper.createMethod(message, body != null);
        request.setMethod(method);

        // set the content type in the response.
        String contentType = MessageHelper.getContentType(message);
        if (contentType != null) {
            // set content-type
            request.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType);
            LOG.trace("Content-Type: {}", contentType);
        }

        // must include HOST header as required by HTTP 1.1
        // use URI as its faster than URL (no DNS lookup)
        URI u = new URI(uri);
        String host = u.getHost();
        request.setHeader(HttpHeaders.Names.HOST, host);
        LOG.trace("Host: {}", host);

        // configure connection to accordingly to keep alive configuration
        // favor using the header from the message
        String connection = message.getHeader(HttpHeaders.Names.CONNECTION, String.class);
        if (connection == null) {
            // fallback and use the keep alive from the configuration
            if (configuration.isKeepAlive()) {
                connection = HttpHeaders.Values.KEEP_ALIVE;
            } else {
                connection = HttpHeaders.Values.CLOSE;
            }
        }
        request.setHeader(HttpHeaders.Names.CONNECTION, connection);
        LOG.trace("Connection: {}", connection);

        return request;
    }

    @Override
    public HeaderFilterStrategy getHeaderFilterStrategy() {
        return headerFilterStrategy;
    }

    @Override
    public void setHeaderFilterStrategy(HeaderFilterStrategy headerFilterStrategy) {
        this.headerFilterStrategy = headerFilterStrategy;
    }
}
