/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.listener.states.listener;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.config.ChunkConfig;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpOutboundRespListener;
import org.wso2.transport.http.netty.listener.SourceHandler;
import org.wso2.transport.http.netty.listener.states.StateContext;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import static org.wso2.transport.http.netty.common.Constants.CHUNKING_CONFIG;
import static org.wso2.transport.http.netty.common.Constants.IDLE_TIMEOUT_TRIGGERED_WHILE_WRITING_100_CONTINUE_RESPONSE;
import static org.wso2.transport.http.netty.common.Constants.REMOTE_CLIENT_CLOSED_BEFORE_INITIATING_OUTBOUND_RESPONSE;
import static org.wso2.transport.http.netty.common.Constants.REMOTE_CLIENT_CLOSED_WHILE_WRITING_100_CONTINUE_RESPONSE;
import static org.wso2.transport.http.netty.common.Constants.REMOTE_CLIENT_TO_HOST_CONNECTION_CLOSED;
import static org.wso2.transport.http.netty.common.Util.createFullHttpResponse;
import static org.wso2.transport.http.netty.common.Util.setupChunkedRequest;
import static org.wso2.transport.http.netty.listener.states.StateUtil.checkChunkingCompatibility;
import static org.wso2.transport.http.netty.listener.states.StateUtil.notifyIfHeaderWriteFailure;

/**
 * Special state of sending 100-continue response
 */
public class Response100ContinueSent extends SendingHeaders {

    private static Logger log = LoggerFactory.getLogger(Response100ContinueSent.class);
    private final StateContext stateContext;
    private final HttpOutboundRespListener outboundResponseListener;
    private final SourceHandler sourceHandler;
    private final float httpVersion;

    Response100ContinueSent(HttpOutboundRespListener outboundResponseListener,
                                   SourceHandler sourceHandler,
                                   StateContext stateContext) {
        super(outboundResponseListener, stateContext);
        this.outboundResponseListener = outboundResponseListener;
        this.chunkConfig = outboundResponseListener.getChunkConfig();
        this.keepAlive = outboundResponseListener.isKeepAlive();
        this.sourceHandler = sourceHandler;
        this.stateContext = stateContext;
        this.httpVersion = Float.parseFloat(outboundResponseListener.getRequestDataHolder().getHttpVersion());
    }

    @Override
    public void readInboundRequestHeaders(HttpCarbonMessage inboundRequestMsg, HttpRequest inboundRequestHeaders) {
        // Not a dependant action of this state.
    }

    @Override
    public void readInboundRequestEntityBody(Object inboundRequestEntityBody) throws ServerConnectorException {
        stateContext.setListenerState(
                new ReceivingEntityBody(stateContext, outboundResponseListener.getInboundRequestMsg(), sourceHandler,
                                        httpVersion));
        stateContext.getListenerState().readInboundRequestEntityBody(inboundRequestEntityBody);
    }

    @Override
    public void writeOutboundResponseHeaders(HttpCarbonMessage outboundResponseMsg, HttpContent httpContent) {
        // Not a dependant action of this state.
    }

    @Override
    public void writeOutboundResponseEntityBody(HttpOutboundRespListener outboundRespListener,
                                                HttpCarbonMessage outboundResponseMsg, HttpContent httpContent) {
        ChunkConfig responseChunkConfig = outboundResponseMsg.getProperty(CHUNKING_CONFIG) != null ?
                (ChunkConfig) outboundResponseMsg.getProperty(CHUNKING_CONFIG) : null;
        if (responseChunkConfig != null) {
            super.setChunkConfig(responseChunkConfig);
        }
        outboundRespStatusFuture = outboundResponseListener.getInboundRequestMsg().getHttpOutboundRespStatusFuture();

        ChannelFuture outboundHeaderFuture;
        if (chunkConfig == ChunkConfig.ALWAYS && checkChunkingCompatibility(String.valueOf(httpVersion), chunkConfig)) {
            setupChunkedRequest(outboundResponseMsg);
            outboundHeaderFuture = writeResponseHeaders(outboundResponseMsg, keepAlive);
            notifyIfHeaderWriteFailure(outboundRespStatusFuture, outboundHeaderFuture,
                                       REMOTE_CLIENT_CLOSED_BEFORE_INITIATING_OUTBOUND_RESPONSE);
        } else {
            CompositeByteBuf allContent = Unpooled.compositeBuffer();
            allContent.addComponent(true, httpContent.content());
            HttpResponse fullOutboundResponse = createFullHttpResponse(outboundResponseMsg,
                                                                       outboundRespListener.getRequestDataHolder()
                                                                               .getHttpVersion(),
                                                                       outboundRespListener.getServerName(),
                                                                       outboundRespListener.isKeepAlive(), allContent);

            outboundHeaderFuture = outboundRespListener.getSourceContext().writeAndFlush(fullOutboundResponse);
            checkForResponseWriteStatus(outboundRespListener.getInboundRequestMsg(), outboundRespStatusFuture,
                                        outboundHeaderFuture);
        }
    }

    @Override
    public void handleAbruptChannelClosure(ServerConnectorFuture serverConnectorFuture) {
        // OutboundResponseStatusFuture will be notified asynchronously via OutboundResponseListener.
        log.error(REMOTE_CLIENT_CLOSED_WHILE_WRITING_100_CONTINUE_RESPONSE);
    }

    @Override
    public ChannelFuture handleIdleTimeoutConnectionClosure(ServerConnectorFuture serverConnectorFuture,
                                                            ChannelHandlerContext ctx) {
        // OutboundResponseStatusFuture will be notified asynchronously via OutboundResponseListener.
        log.error(IDLE_TIMEOUT_TRIGGERED_WHILE_WRITING_100_CONTINUE_RESPONSE);
        return null;
    }

    private void checkForResponseWriteStatus(HttpCarbonMessage inboundRequestMsg,
                                             HttpResponseFuture outboundRespStatusFuture, ChannelFuture channelFuture) {
        channelFuture.addListener(writeOperationPromise -> {
            Throwable throwable = writeOperationPromise.cause();
            if (throwable != null) {
                if (throwable instanceof ClosedChannelException) {
                    throwable = new IOException(REMOTE_CLIENT_TO_HOST_CONNECTION_CLOSED);
                }
                outboundRespStatusFuture.notifyHttpListener(throwable);
            } else {
                outboundRespStatusFuture.notifyHttpListener(inboundRequestMsg);
            }
        });
    }
}
