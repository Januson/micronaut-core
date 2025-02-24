/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.netty.stream;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.netty.reactive.CancelledSubscriber;
import io.micronaut.http.netty.reactive.HandlerPublisher;
import io.micronaut.http.netty.reactive.HandlerSubscriber;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.reactivestreams.Publisher;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Handler that reads {@link HttpRequest} messages followed by {@link HttpContent} messages and produces
 * {@link StreamedHttpRequest} messages, and converts written {@link StreamedHttpResponse} messages into
 * {@link HttpResponse} messages followed by {@link HttpContent} messages.
 * <p>
 * This allows request and response bodies to be handled using reactive streams.
 * <p>
 * There are two types of messages that this handler will send down the chain, {@link StreamedHttpRequest},
 * and {@link FullHttpRequest}. If {@link io.netty.channel.ChannelOption#AUTO_READ} is false for the channel,
 * then any {@link StreamedHttpRequest} messages <em>must</em> be subscribed to consume the body, otherwise
 * it's possible that no read will be done of the messages.
 * <p>
 * There are three types of messages that this handler accepts for writing, {@link StreamedHttpResponse},
 * {@link WebSocketHttpResponse} and {@link io.netty.handler.codec.http.FullHttpResponse}. Writing any other messages
 * may potentially lead to HTTP message mangling.
 * <p>
 * As long as messages are returned in the order that they arrive, this handler implicitly supports HTTP
 * pipelining.
 *
 * @author jroper
 * @author Graeme Rocher
 */
@Internal
public class HttpStreamsServerHandler extends HttpStreamsHandler<HttpRequest, HttpResponse> {

    private HttpRequest lastRequest = null;
    private HttpResponse webSocketResponse = null;
    private ChannelPromise webSocketResponseChannelPromise = null;
    private int inFlight = 0;
    private boolean continueExpected = true;
    private boolean sendContinue = false;
    private boolean close = false;

    private final List<ChannelHandler> dependentHandlers;

    /**
     * Default constructor.
     */
    public HttpStreamsServerHandler() {
        this(Collections.emptyList());
    }

    /**
     * Create a new handler that is depended on by the given handlers.
     * <p>
     * The list of dependent handlers will be removed from the chain when this handler is removed from the chain,
     * for example, when the connection is upgraded to use websockets. This is useful, for example, for removing
     * the reactive streams publisher/subscriber from the chain in that event.
     *
     * @param dependentHandlers The handlers that depend on this handler.
     */
    public HttpStreamsServerHandler(List<ChannelHandler> dependentHandlers) {
        super(HttpRequest.class, HttpResponse.class);
        this.dependentHandlers = dependentHandlers;
    }

    @Override
    protected boolean hasBody(HttpRequest request) {
        // if there's a decoder failure (e.g. invalid header), don't expect the body to come in
        if (request.decoderResult().isFailure()) {
            return false;
        }
        // Http requests don't have a body if they define 0 content length, or no content length and no transfer
        // encoding
        int contentLength;
        try {
            contentLength = HttpUtil.getContentLength(request, 0);
        } catch (NumberFormatException e) {
            // handle invalid content length, https://github.com/netty/netty/issues/12113
            contentLength = 0;
        }
        return contentLength != 0 || HttpUtil.isTransferEncodingChunked(request);
    }

    @Override
    protected HttpRequest createEmptyMessage(HttpRequest request) {
        return new EmptyHttpRequest(request);
    }

    @Override
    protected HttpRequest createStreamedMessage(HttpRequest httpRequest, Publisher<? extends HttpContent> stream) {
        return new DelegateStreamedHttpRequest(httpRequest, stream);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Set to false, since if it was true, and the client is sending data, then the
        // client must no longer be expecting it (due to a timeout, for example).
        continueExpected = false;
        sendContinue = false;

        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            lastRequest = request;
            if (HttpUtil.is100ContinueExpected(request)) {
                continueExpected = true;
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    protected void receivedInMessage(ChannelHandlerContext ctx) {
        inFlight++;
    }

    @Override
    protected void sentOutMessage(ChannelHandlerContext ctx) {
        inFlight--;
        if (inFlight == 1 && continueExpected && sendContinue) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(lastRequest.protocolVersion(), HttpResponseStatus.CONTINUE));
            sendContinue = false;
            continueExpected = false;
        }

        if (close) {
            ctx.close();
        }
    }

    @Override
    protected void unbufferedWrite(ChannelHandlerContext ctx, HttpResponse message, ChannelPromise promise) {

        if (message instanceof WebSocketHttpResponse) {
            if ((lastRequest instanceof FullHttpRequest) || !hasBody(lastRequest)) {
                handleWebSocketResponse(ctx, message, promise);
            } else {
                // If the response has a streamed body, then we can't send the WebSocket response until we've received
                // the body.
                webSocketResponse = message;
                webSocketResponseChannelPromise = promise;
            }
        } else {
            if (lastRequest.protocolVersion().isKeepAliveDefault()) {
                if (message.headers().contains(HttpHeaderNames.CONNECTION, "close", true)) {
                    close = true;
                }
            } else {
                if (!message.headers().contains(HttpHeaderNames.CONNECTION, "keep-alive", true)) {
                    close = true;
                }
            }
            if (inFlight == 1 && continueExpected) {
                HttpUtil.setKeepAlive(message, false);
                close = true;
                continueExpected = false;
            }
            // According to RFC 7230 a server MUST NOT send a Content-Length or a Transfer-Encoding when the status
            // code is 1xx or 204, also a status code 304 may not have a Content-Length or Transfer-Encoding set.
            if (!HttpUtil.isContentLengthSet(message) && !HttpUtil.isTransferEncodingChunked(message) && canHaveBody(message)) {
                HttpUtil.setKeepAlive(message, false);
                close = true;
            }
            super.unbufferedWrite(ctx, message, promise);
        }
    }

    @Override
    protected boolean isValidOutMessage(Object msg) {
        return msg instanceof FullHttpResponse || msg instanceof StreamedHttpResponse || msg instanceof WebSocketHttpResponse;
    }

    private boolean canHaveBody(HttpResponse message) {
        HttpResponseStatus status = message.status();
        // All 1xx (Informational), 204 (No Content), and 304 (Not Modified)
        // responses do not include a message body
        return !(status == HttpResponseStatus.CONTINUE || status == HttpResponseStatus.SWITCHING_PROTOCOLS ||
            status == HttpResponseStatus.PROCESSING || status == HttpResponseStatus.NO_CONTENT ||
            status == HttpResponseStatus.NOT_MODIFIED);
    }

    @Override
    protected void consumedInMessage(ChannelHandlerContext ctx) {
        if (webSocketResponse != null) {
            handleWebSocketResponse(ctx, webSocketResponse, webSocketResponseChannelPromise);
            webSocketResponse = null;
            webSocketResponseChannelPromise = null;
        }
        if (inFlight == 0) {
            // normally, after writing the response, the routing handler triggers a read() for the
            // next request. However, if at this point the request is not fully read yet (e.g.
            // still missing a LastHttpContent), then that read() call will simply read the
            // remaining content, and the HandlerPublisher also won't trigger more read()s since
            // it's complete. To prevent the connection from being stuck in that case, we trigger a
            // read here.
            ctx.read();
        }
    }

    private void handleWebSocketResponse(ChannelHandlerContext ctx, HttpResponse message, ChannelPromise promise) {
        WebSocketHttpResponse response = (WebSocketHttpResponse) message;
        WebSocketServerHandshaker handshaker = response.handshakerFactory().newHandshaker(lastRequest);

        if (handshaker == null) {
            HttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.UPGRADE_REQUIRED);
            res.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, WebSocketVersion.V13.toHttpHeaderValue());
            HttpUtil.setContentLength(res, 0);
            super.unbufferedWrite(ctx, message, promise);
            response.subscribe(new CancelledSubscriber<>());
        } else {
            // First, insert new handlers in the chain after us for handling the websocket
            ChannelPipeline pipeline = ctx.pipeline();
            HandlerPublisher<WebSocketFrame> publisher = new HandlerPublisher<>(ctx.executor()) {
                @Override
                protected boolean acceptInboundMessage(Object msg) {
                    return msg instanceof WebSocketFrame;
                }
            };
            HandlerSubscriber<WebSocketFrame> subscriber = new HandlerSubscriber<>(ctx.executor());
            pipeline.addAfter(ctx.executor(), ctx.name(), "websocket-subscriber", subscriber);
            pipeline.addAfter(ctx.executor(), ctx.name(), "websocket-publisher", publisher);

            // Now remove ourselves from the chain
            ctx.pipeline().remove(ctx.name());

            // Now do the handshake
            // Wrap the request in an empty request because we don't need the WebSocket handshaker ignoring the body,
            // we already have handled the body.
            handshaker.handshake(ctx.channel(), new EmptyHttpRequest(lastRequest));

            // And hook up the subscriber/publishers
            response.subscribe(subscriber);
            publisher.subscribe(response);
        }

    }

    @Override
    protected void bodyRequested(ChannelHandlerContext ctx) {
        if (continueExpected) {
            if (inFlight == 1) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(lastRequest.protocolVersion(), HttpResponseStatus.CONTINUE));
                continueExpected = false;
            } else {
                sendContinue = true;
            }
        }
    }

    @Override
    protected final boolean isClient() {
        return false;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        for (ChannelHandler dependent : dependentHandlers) {
            try {
                ctx.pipeline().remove(dependent);
            } catch (NoSuchElementException e) {
                // Ignore, maybe something else removed it
            }
        }
    }
}
