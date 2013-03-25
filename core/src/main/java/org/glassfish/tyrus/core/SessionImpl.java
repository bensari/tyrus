/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.tyrus.core;


import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.glassfish.tyrus.spi.SPIRemoteEndpoint;

/**
 * Implementation of the {@link Session}.
 *
 * @author Danny Coward (danny.coward at oracle.com)
 * @author Stepan Kopriva (stepan.kopriva at oracle.com)
 * @author Martin Matula (martin.matula at oracle.com)
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 */
public class SessionImpl implements Session {

    private final WebSocketContainer container;
    private final EndpointWrapper endpoint;
    private final RemoteEndpointWrapper.Basic basicRemote;
    private final RemoteEndpointWrapper.Async asyncRemote;
    private String negotiatedSubprotocol;
    private List<Extension> negotiatedExtensions;
    private final boolean isSecure;
    private final URI uri;
    private final String queryString;
    private final Map<String, String> pathParameters;
    private final Principal userPrincipal;
    private int maxBinaryMessageBufferSize = Integer.MAX_VALUE;
    private int maxTextMessageBufferSize = Integer.MAX_VALUE;
    private long maxIdleTimeout = 0;
    private Timer timer;

    private final String id = UUID.randomUUID().toString();
    private static final Logger LOGGER = Logger.getLogger(SessionImpl.class.getName());
    private final Map<String, Object> userProperties = new HashMap<String, Object>();
    private final MessageHandlerManager handlerManager;
    private static final String SESSION_CLOSED = "The connection has been closed.";
    private final AtomicReference<State> state = new AtomicReference<State>(State.RUNNING);

    private final TextBuffer textBuffer = new TextBuffer();
    private final BinaryBuffer binaryBuffer = new BinaryBuffer();
    private ReaderBuffer readerBuffer;
    private InputStreamBuffer inputStreamBuffer;

    /**
     * Session state.
     */
    public enum State {

        /**
         * {@link Session} is running and is not receiving partial messages on registered {@link MessageHandler.Whole}.
         */
        RUNNING,

        /**
         * {@link Session} is currently receiving text partial message on registered {@link MessageHandler.Whole}.
         */
        RECEIVING_TEXT,

        /**
         * {@link Session} is currently receiving binary partial message on registered {@link MessageHandler.Whole}.
         */
        RECEIVING_BINARY,

        /**
         * {@link Session} is being closed.
         */
        CLOSING,

        /**
         * {@link Session} has been already closed.
         */
        CLOSED
    }

    SessionImpl(WebSocketContainer container, SPIRemoteEndpoint remoteEndpoint, EndpointWrapper endpointWrapper,
                String subprotocol, List<Extension> extensions, boolean isSecure,
                URI uri, String queryString, Map<String, String> pathParameters, Principal principal) {
        this.container = container;
        this.endpoint = endpointWrapper;
        this.negotiatedSubprotocol = subprotocol;
        this.negotiatedExtensions = extensions == null ? Collections.<Extension>emptyList() : Collections.unmodifiableList(extensions);
        this.isSecure = isSecure;
        this.uri = uri;
        this.queryString = queryString;
        this.pathParameters = pathParameters == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(pathParameters);
        this.basicRemote = new RemoteEndpointWrapper.Basic(this, remoteEndpoint, endpointWrapper);
        this.asyncRemote = new RemoteEndpointWrapper.Async(this, remoteEndpoint, endpointWrapper, 0);
        this.handlerManager = MessageHandlerManager.fromDecoderInstances(endpointWrapper.getDecoders());
        this.userPrincipal = principal;

        if(container != null) {
            maxTextMessageBufferSize = container.getDefaultMaxTextMessageBufferSize();
            maxBinaryMessageBufferSize = container.getDefaultMaxBinaryMessageBufferSize();
        }
    }

    /**
     * Web Socket protocol version used.
     *
     * @return protocol version
     */
    @Override
    public String getProtocolVersion() {
        checkConnectionState(State.CLOSED);
        return "13"; // TODO
    }

    @Override
    public String getNegotiatedSubprotocol() {
        checkConnectionState(State.CLOSED);
        return negotiatedSubprotocol;
    }

    @Override
    public RemoteEndpoint.Async getAsyncRemote() {
        checkConnectionState(State.CLOSED, State.CLOSING);
        return asyncRemote;
    }

    @Override
    public RemoteEndpoint.Basic getBasicRemote() {
        checkConnectionState(State.CLOSED, State.CLOSING);
        return basicRemote;
    }

    @Override
    public boolean isOpen() {
        return (!(state.get() == State.CLOSED || state.get() == State.CLOSING));
    }

    @Override
    public void close() throws IOException {
        changeStateToClosing();
        basicRemote.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "no reason given"));
    }

    /**
     * Closes the underlying connection this session is based upon.
     */
    @Override
    public void close(CloseReason closeReason) throws IOException {
        checkConnectionState(State.CLOSED);
        changeStateToClosing();
        basicRemote.close(closeReason);
    }

    @Override
    public int getMaxBinaryMessageBufferSize() {
        checkConnectionState(State.CLOSED);
        return maxBinaryMessageBufferSize;
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int maxBinaryMessageBufferSize) {
        checkConnectionState(State.CLOSED);
        this.maxBinaryMessageBufferSize = maxBinaryMessageBufferSize;
    }

    @Override
    public int getMaxTextMessageBufferSize() {
        checkConnectionState(State.CLOSED);
        return maxTextMessageBufferSize;
    }

    @Override
    public void setMaxTextMessageBufferSize(int maxTextMessageBufferSize) {
        checkConnectionState(State.CLOSED);
        this.maxTextMessageBufferSize = maxTextMessageBufferSize;
    }

    @Override
    public Set<Session> getOpenSessions() {
        checkConnectionState(State.CLOSED);
        return Collections.unmodifiableSet(endpoint.getOpenSessions());
    }

    @Override
    public List<Extension> getNegotiatedExtensions() {
        checkConnectionState(State.CLOSED);
        return negotiatedExtensions;
    }

    @Override
    public long getMaxIdleTimeout() {
        checkConnectionState(State.CLOSED);
        return maxIdleTimeout;
    }

    @Override
    public void setMaxIdleTimeout(long maxIdleTimeout) {
        checkConnectionState(State.CLOSED);
        this.maxIdleTimeout = maxIdleTimeout;
        restartTimer();
    }

    @Override
    public boolean isSecure() {
        checkConnectionState(State.CLOSED);
        return isSecure;
    }

    @Override
    public WebSocketContainer getContainer() {
        checkConnectionState(State.CLOSED);
        return this.container;
    }


    @Override
    public void addMessageHandler(MessageHandler handler) {
        checkConnectionState(State.CLOSED);
        synchronized (handlerManager) {
            handlerManager.addMessageHandler(handler);
        }
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        checkConnectionState(State.CLOSED);
        synchronized (handlerManager) {
            return handlerManager.getMessageHandlers();
        }
    }

    @Override
    public void removeMessageHandler(MessageHandler handler) {
        checkConnectionState(State.CLOSED);
        synchronized (handlerManager) {
            handlerManager.removeMessageHandler(handler);
        }
    }

    @Override
    public URI getRequestURI() {
        checkConnectionState(State.CLOSED);
        return uri;
    }

    // TODO: this method should be deleted?
    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        checkConnectionState(State.CLOSED);
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getPathParameters() {
        checkConnectionState(State.CLOSED);
        return pathParameters;
    }

    @Override
    public Map<String, Object> getUserProperties() {
        checkConnectionState(State.CLOSED);
        return userProperties;
    }

    @Override
    public String getQueryString() {
        checkConnectionState(State.CLOSED);
        return queryString;
    }

    @Override
    public String getId() {
        checkConnectionState(State.CLOSED);
        return id;
    }

    @Override
    public Principal getUserPrincipal() {
        checkConnectionState(State.CLOSED);
        return userPrincipal;
    }

    void restartTimer(){
        if(timer != null){
            timer.cancel();
        }

        if(this.getMaxIdleTimeout() < 1){
            return;
        }

        timer = new Timer();
        timer.schedule(new SessionTimerTask(), this.getMaxIdleTimeout());
    }

    private void checkConnectionState(State... states) {
        for (State s : states) {
            if(state.get() == s){
                throw new IllegalStateException(SESSION_CLOSED);
            }
        }
    }

    void setNegotiatedSubprotocol(String negotiatedSubprotocol) {
        this.negotiatedSubprotocol = negotiatedSubprotocol == null ? "" : negotiatedSubprotocol;
    }

    void setNegotiatedExtensions(List<Extension> negotiatedExtensions) {
        this.negotiatedExtensions = Collections.unmodifiableList(new ArrayList<Extension>(negotiatedExtensions));
    }

    private void checkMessageSize(Object message, long maxMessageSize) {
        if (maxMessageSize != -1) {
            final long messageSize = (message instanceof String ? ((String) message).getBytes().length :
                    ((ByteBuffer) message).remaining());

            if (messageSize > maxMessageSize) {
                throw new MaxMessageSizeException(String.format("Message too long; allowed message size is %d bytes. (Current message is %d bytes).", maxMessageSize, messageSize));
            }
        }
    }

    void notifyMessageHandlers(Object message, List<CoderWrapper<Decoder>> availableDecoders) {
        checkConnectionState(State.CLOSED);

        boolean decoded = false;

        if (availableDecoders.isEmpty()) {
            LOGGER.severe("No decoder found");
        }

        for (CoderWrapper<Decoder> decoder : availableDecoders) {
            for (MessageHandler mh : this.getOrderedMessageHandlers()) {
                Class<?> type;
                if ((mh instanceof MessageHandler.Whole)
                        && (type = MessageHandlerManager.getHandlerType(mh)).isAssignableFrom(decoder.getType())) {

                    if (mh instanceof BasicMessageHandler) {
                        checkMessageSize(message, ((BasicMessageHandler) mh).getMaxMessageSize());
                    }

                    Object object = endpoint.decodeCompleteMessage(this, message, type);
                    if (object != null) {
                        //noinspection unchecked
                        ((MessageHandler.Whole) mh).onMessage(object);
                        decoded = true;
                        break;
                    }
                }
            }
            if (decoded) {
                break;
            }
        }
    }

    <T> MessageHandler.Whole<T> getMessageHandler(Class<T> c) {
        for (MessageHandler mh : this.getOrderedMessageHandlers()) {
            if (MessageHandlerManager.getHandlerType(mh) == c) {
                return (MessageHandler.Whole<T>) mh;
            }
        }

        return null;
    }

    void notifyMessageHandlers(Object message, boolean last) {
        checkConnectionState(State.CLOSED);
        boolean handled = false;

        for (MessageHandler handler : this.getMessageHandlers()) {
            if ((handler instanceof MessageHandler.Partial) &&
                    MessageHandlerManager.getHandlerType(handler).isAssignableFrom(message.getClass())) {

                if (handler instanceof AsyncMessageHandler) {
                    checkMessageSize(message, ((AsyncMessageHandler) handler).getMaxMessageSize());
                }

                //noinspection unchecked
                ((MessageHandler.Partial) handler).onMessage(message, last);
                handled = true;
                break;
            }
        }

        if (!handled) {
            if (message instanceof ByteBuffer) {
                notifyMessageHandlers(((ByteBuffer) message).array(), last);
            } else {
                LOGGER.severe("Unhandled text message in EndpointWrapper");
            }
        }
    }

    void notifyPongHandler(PongMessage pongMessage) {
        final Set<MessageHandler> messageHandlers = this.getMessageHandlers();
        for (MessageHandler handler : messageHandlers) {
            if (MessageHandlerManager.getHandlerType(handler).equals(PongMessage.class)) {
                ((MessageHandler.Whole<PongMessage>) handler).onMessage(pongMessage);
            }
        }
    }

    boolean isWholeTextHandlerPresent() {
        return handlerManager.isWholeTextHandlerPresent();
    }

    boolean isWholeBinaryHandlerPresent() {
        return handlerManager.isWholeBinaryHandlerPresent();
    }

    boolean isPartialTextHandlerPresent() {
        return handlerManager.isPartialTextHandlerPresent();
    }

    boolean isPartialBinaryHandlerPresent() {
        return handlerManager.isPartialBinaryHandlerPresent();
    }

    boolean isReaderHandlerPresent() {
        return handlerManager.isReaderHandlerPresent();
    }

    boolean isInputStreamHandlerPresent() {
        return handlerManager.isInputStreamHandlerPresent();
    }

    boolean isPongHandlerPreset() {
        return handlerManager.isPongHandlerPresent();
    }

    private List<MessageHandler> getOrderedMessageHandlers() {
        checkConnectionState(State.CLOSED);
        Set<MessageHandler> handlers = this.getMessageHandlers();
        ArrayList<MessageHandler> result = new ArrayList<MessageHandler>();

        result.addAll(handlers);
        Collections.sort(result, new MessageHandlerComparator());

        return result;
    }

    private class MessageHandlerComparator implements Comparator<MessageHandler> {

        @Override
        public int compare(MessageHandler o1, MessageHandler o2) {
            if (o1 instanceof MessageHandler.Whole) {
                if (o2 instanceof MessageHandler.Whole) {
                    Class<?> type1 = MessageHandlerManager.getHandlerType(o1);
                    Class<?> type2 = MessageHandlerManager.getHandlerType(o2);

                    if (type1.isAssignableFrom(type2)) {
                        return 1;
                    } else if (type2.isAssignableFrom(type1)) {
                        return -1;
                    } else {
                        return 0;
                    }
                } else {
                    return 1;
                }
            } else if (o2 instanceof MessageHandler.Whole) {
                return 1;
            }
            return 0;
        }
    }

    State getState() {
        return state.get();
    }

    TextBuffer getTextBuffer() {
        return textBuffer;
    }

    BinaryBuffer getBinaryBuffer() {
        return binaryBuffer;
    }

    /**
     * Set the state of the {@link Session}.
     *
     * @param state the newly set state.
     */
    public void setState(State state) {
        checkConnectionState(State.CLOSED);
        this.state.set(state);
    }

    ReaderBuffer getReaderBuffer() {
        return readerBuffer;
    }

    InputStreamBuffer getInputStreamBuffer() {
        return inputStreamBuffer;
    }

    void setReaderBuffer(ReaderBuffer readerBuffer) {
        this.readerBuffer = readerBuffer;
    }

    void setInputStreamBuffer(InputStreamBuffer inputStreamBuffer) {
        this.inputStreamBuffer = inputStreamBuffer;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SessionImpl");
        sb.append("{uri=").append(uri);
        sb.append(", id='").append(id).append('\'');
        sb.append(", endpoint=").append(endpoint);
        sb.append('}');
        return sb.toString();
    }

    private class SessionTimerTask extends TimerTask {

        @Override
        public void run() {
            try {
                SessionImpl.this.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Session closed by the container because of the idle timeout."));
            } catch (IOException e) {
                LOGGER.log(Level.FINE,"Session could not been closed. "+e.getMessage());
            }
        }
    }

    private void changeStateToClosing(){
        state.compareAndSet(State.RUNNING, State.CLOSING);
        state.compareAndSet(State.RECEIVING_BINARY, State.CLOSING);
        state.compareAndSet(State.RECEIVING_TEXT, State.CLOSING);
    }
}
