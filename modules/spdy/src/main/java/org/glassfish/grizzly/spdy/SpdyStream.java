/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
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
package org.glassfish.grizzly.spdy;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.CloseReason;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.attributes.AttributeStorage;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.OutputSink;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.CompositeBuffer.DisposeOrder;
import org.glassfish.grizzly.spdy.frames.RstStreamFrame;
import org.glassfish.grizzly.utils.DataStructures;
import org.glassfish.grizzly.utils.Futures;

import static org.glassfish.grizzly.spdy.Constants.*;
/**
 * The abstraction representing SPDY stream.
 * 
 * @author Grizzly team
 */
public class SpdyStream implements AttributeStorage, OutputSink, Closeable {
    public static final String SPDY_STREAM_ATTRIBUTE = SpdyStream.class.getName();

    private enum CompletionUnit {
        Input, Output, Complete
    }
    
    private static final Attribute<SpdyStream> HTTP_RQST_SPDY_STREAM_ATTR =
            AttributeBuilder.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("http.request.spdy.stream");
    
    private final HttpRequestPacket spdyRequest;
    private final int streamId;
    private final int associatedToStreamId;
    private final int priority;
    private final int slot;
    private final boolean isUnidirectional;
    
    private final SpdySession spdySession;
    
    private final AttributeHolder attributes =
            AttributeBuilder.DEFAULT_ATTRIBUTE_BUILDER.createSafeAttributeHolder();

    final StreamInputBuffer inputBuffer;
    final StreamOutputSink outputSink;
    
    // closeReasonRef, "null" value means the connection is open.
    final AtomicReference<CloseReason> closeReasonRef =
            new AtomicReference<CloseReason>();
    private final Queue<CloseListener> closeListeners =
            new ConcurrentLinkedQueue<CloseListener>();
    private final AtomicInteger completeFinalizationCounter = new AtomicInteger();
    
    // flag, which is indicating if SpdyStream processing has been marked as complete by external code
    volatile boolean isProcessingComplete;
    
    // flag, which is indicating if SynStream or SynReply frames have already come for this SpdyStream
    private boolean isSynFrameRcv;
    
    private Map<String, PushResource> associatedResourcesToPush;
    private Set<SpdyStream> associatedSpdyStreams;
        
    public static SpdyStream getSpdyStream(final HttpHeader httpHeader) {
        final HttpRequestPacket request;
        
        if (httpHeader.isRequest()) {
            assert httpHeader instanceof HttpRequestPacket;
            request = (HttpRequestPacket) httpHeader;
        } else {
            assert httpHeader instanceof HttpResponsePacket;
            request = ((HttpResponsePacket) httpHeader).getRequest();
        }
        
        
        if (request != null) {
            return HTTP_RQST_SPDY_STREAM_ATTR.get(request);
        }
        
        return null;
    }

    protected SpdyStream(final SpdySession spdySession,
            final HttpRequestPacket spdyRequest,
            final int streamId, final int associatedToStreamId,
            final int priority, final int slot,
            final boolean isUnidirectional) {
        this.spdySession = spdySession;
        this.spdyRequest = spdyRequest;
        this.streamId = streamId;
        this.associatedToStreamId = associatedToStreamId;
        this.priority = priority;
        this.slot = slot;
        this.isUnidirectional = isUnidirectional;
        
        inputBuffer = new StreamInputBuffer(this);
        outputSink = new StreamOutputSink(this);
        
        HTTP_RQST_SPDY_STREAM_ATTR.set(spdyRequest, this);
    }

    SpdySession getSpdySession() {
        return spdySession;
    }

    public int getPeerWindowSize() {
        return spdySession.getPeerStreamWindowSize();
    }

    public int getLocalWindowSize() {
        return spdySession.getLocalStreamWindowSize();
    }
    
    /**
     * @return the number of writes (not bytes), that haven't reached network layer
     */
    public int getUnflushedWritesCount() {
        return outputSink.getUnflushedWritesCount() ;
    }
    
    public HttpRequestPacket getSpdyRequest() {
        return spdyRequest;
    }
    
    public HttpResponsePacket getSpdyResponse() {
        return spdyRequest.getResponse();
    }
    
    public PushResource addPushResource(final String url,
            final PushResource pushResource) {
        
        if (associatedResourcesToPush == null) {
            associatedResourcesToPush = new HashMap<String, PushResource>();
        }
        
        return associatedResourcesToPush.put(url, pushResource);
    }

    public PushResource removePushResource(final String url) {
        
        if (associatedResourcesToPush == null) {
            return null;
        }
        
        return associatedResourcesToPush.remove(url);
    }
    
    public int getStreamId() {
        return streamId;
    }

    public int getAssociatedToStreamId() {
        return associatedToStreamId;
    }

    public int getPriority() {
        return priority;
    }

    public int getSlot() {
        return slot;
    }

    public boolean isUnidirectional() {
        return isUnidirectional;
    }
    
    public boolean isLocallyInitiatedStream() {
        assert streamId > 0;
        
        return spdySession.isServer() ^ ((streamId % 2) != 0);
        
//        Same as
//        return spdySession.isServer() ?
//                (streamId % 2) == 0 :
//                (streamId % 2) == 1;        
    }

    @Override
    public boolean isOpen() {
        return completeFinalizationCounter.get() < 2;
    }

    @Override
    public void assertOpen() throws IOException {
        if (!isOpen()) {
            final CloseReason closeReason = closeReasonRef.get();
            assert closeReason != null;
            
            throw new IOException("closed", closeReason.getCause());
        }
    }

    @Override
    public AttributeHolder getAttributes() {
        return attributes;
    }

    @Override
    @Deprecated
    public boolean canWrite(int length) {
        return canWrite();
    }
    
    @Override
    public boolean canWrite() {
        return outputSink.canWrite();
    }

    @Override
    @Deprecated
    public void notifyCanWrite(final WriteHandler handler, final int length) {
        notifyCanWrite(handler);
    }
    
    @Override
    public void notifyCanWrite(final WriteHandler writeHandler) {
        outputSink.notifyWritePossible(writeHandler);
    }

    StreamOutputSink getOutputSink() {
        return outputSink;
    }
    
    @Override
    public GrizzlyFuture<Closeable> terminate() {
        final FutureImpl<Closeable> future = Futures.createSafeFuture();
        close0(Futures.toCompletionHandler(future), CloseType.LOCALLY, null, false);
        
        return future;
    }

    @Override
    public void terminateSilently() {
        close0(null, CloseType.LOCALLY, null, false);
    }

    @Override
    public void terminateWithReason(final IOException cause) {
        close0(null, CloseType.LOCALLY, cause, false);
    }
    
    @Override
    public GrizzlyFuture<Closeable> close() {
        final FutureImpl<Closeable> future = Futures.createSafeFuture();
        close0(Futures.toCompletionHandler(future), CloseType.LOCALLY, null, true);
        
        return future;
    }

    @Override
    public void closeSilently() {
        close0(null, CloseType.LOCALLY, null, true);
    }

    /**
     * {@inheritDoc}
     * @deprecated please use {@link #close()} with the following {@link GrizzlyFuture#addCompletionHandler(org.glassfish.grizzly.CompletionHandler)} call
     */
    @Override
    public void close(final CompletionHandler<Closeable> completionHandler) {
        close0(completionHandler, CloseType.LOCALLY, null, true);
    }

    @Override
    public void closeWithReason(final IOException cause) {
        close0(null, CloseType.LOCALLY, cause, false);
    }
    
    void close0(
            final CompletionHandler<Closeable> completionHandler,
            final CloseType closeType,
            final IOException cause,
            final boolean isCloseOutputGracefully) {
        
        if (closeReasonRef.get() != null) {
            return;
        }
        
        if (closeReasonRef.compareAndSet(null,
                new CloseReason(closeType, cause))) {
            
            final Termination termination = closeType == CloseType.LOCALLY ?
                    LOCAL_CLOSE_TERMINATION : 
                    PEER_CLOSE_TERMINATION;
            
            // Terminate the input, dicard already bufferred data
            inputBuffer.terminate(termination);
            
            if (isCloseOutputGracefully) {
                outputSink.close();
            } else {
                // Terminate the output, discard all the pending data in the output buffer
                outputSink.terminate(termination);
            }
            
            notifyCloseListeners();

            if (completionHandler != null) {
                completionHandler.completed(this);
            }
        }
    }

    /**
     * Notifies the SpdyStream that it's been closed remotely.
     */
    void closedRemotely() {
        // Schedule (add to the stream's input queue) the Termination,
        // which will be invoked once read by the user code.
        // This way we simulate Java Socket behavior
        inputBuffer.close(
                new Termination(TerminationType.PEER_CLOSE, CLOSED_BY_PEER_STRING) {
            @Override
            public void doTask() {
                close0(null, CloseType.REMOTELY, null, false);
            }
        });
    }
    
    /**
     * Notify the SpdyStream that peer sent RST_FRAME.
     */
    void resetRemotely() {
        if (closeReasonRef.compareAndSet(null,
                new CloseReason(CloseType.REMOTELY, null))) {
            // initiat graceful shutdown for input, so user is able to read
            // the bufferred data
            inputBuffer.close(RESET_TERMINATION);
            
            // forcibly terminate the output, so no more data will be sent
            outputSink.terminate(RESET_TERMINATION);
        }
        
        rstAssociatedStreams();
    }
    
    void onProcessingComplete() {
        isProcessingComplete = true;
        if (closeReasonRef.compareAndSet(null,
                new CloseReason(CloseType.LOCALLY, null))) {
            
            final Termination termination = 
                    LOCAL_CLOSE_TERMINATION;
            
            inputBuffer.terminate(termination);
            outputSink.close();
            
            notifyCloseListeners();
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public void addCloseListener(CloseListener closeListener) {
        CloseReason closeReason = closeReasonRef.get();
        
        // check if connection is still open
        if (closeReason == null) {
            // add close listener
            closeListeners.add(closeListener);
            // check the connection state again
            closeReason = closeReasonRef.get();
            if (closeReason != null && closeListeners.remove(closeListener)) {
                // if connection was closed during the method call - notify the listener
                try {
                    closeListener.onClosed(this, closeReason.getType());
                } catch (IOException ignored) {
                }
            }
        } else { // if connection is closed - notify the listener
            try {
                closeListener.onClosed(this, closeReason.getType());
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public boolean removeCloseListener(CloseListener closeListener) {
        return closeListeners.remove(closeListener);
    }

    void onInputClosed() {
        if (completeFinalizationCounter.incrementAndGet() == 2) {
            closeStream();
        }
    }

    void onOutputClosed() {
        if (completeFinalizationCounter.incrementAndGet() == 2) {
            closeStream();
        }
    }
    
    void onSynFrameRcv() throws SpdyStreamException {
        if (!isSynFrameRcv) {
            isSynFrameRcv = true;
        } else {
            inputBuffer.close(UNEXPECTED_FRAME_TERMINATION);
            throw new SpdyStreamException(getStreamId(),
                    RstStreamFrame.PROTOCOL_ERROR, "Only one syn frame is allowed");
        }
    }
    
    private Buffer cachedInputBuffer;
    private boolean cachedIsLast;
    
    SpdyStreamException assertCanAcceptData() {
        if (isUnidirectional() && isLocallyInitiatedStream()) {
            return new SpdyStreamException(getStreamId(),
                    RstStreamFrame.PROTOCOL_ERROR,
                    "Data frame received on unidirectional stream");
        }

        if (!isSynFrameRcv) {
            close0(null, CloseType.LOCALLY,
                    new IOException("DataFrame came before Syn frame"), false);
            
            return new SpdyStreamException(getStreamId(),
                    RstStreamFrame.PROTOCOL_ERROR, "DataFrame came before Syn frame");
        }
        
        return null;
    }
    
    void offerInputData(final Buffer data, final boolean isLast)
            throws SpdyStreamException {
        final boolean isFirstBufferCached = (cachedInputBuffer == null);
        cachedIsLast |= isLast;
        cachedInputBuffer = Buffers.appendBuffers(
                spdySession.getMemoryManager(),
                cachedInputBuffer, data);
        
        if (isFirstBufferCached) {
            spdySession.streamsToFlushInput.add(this);
        }
    }
    
    void flushInputData() {
        final Buffer cachedInputBufferLocal = cachedInputBuffer;
        final boolean cachedIsLastLocal = cachedIsLast;
        cachedInputBuffer = null;
        cachedIsLast = false;
        
        if (cachedInputBufferLocal != null) {
            if (cachedInputBufferLocal.isComposite()) {
                ((CompositeBuffer) cachedInputBufferLocal).allowInternalBuffersDispose(true);
                ((CompositeBuffer) cachedInputBufferLocal).allowBufferDispose(true);
                ((CompositeBuffer) cachedInputBufferLocal).disposeOrder(DisposeOrder.LAST_TO_FIRST);
            }
            
            final int size = cachedInputBufferLocal.remaining();
            if (!inputBuffer.offer(cachedInputBufferLocal, cachedIsLastLocal)) {
                // if we can't add this buffer to the stream input buffer -
                // we have to release the part of connection window allocated
                // for the buffer
                spdySession.sendWindowUpdate(size);
            }
        }
    }
    
    HttpContent pollInputData() throws IOException {
        return inputBuffer.poll();
    }
    
    private void closeStream() {
        spdySession.deregisterStream(this);
    }
    
    HttpHeader getInputHttpHeader() {
        return (isLocallyInitiatedStream() ^ isUnidirectional()) ?
                spdyRequest.getResponse() :
                spdyRequest;
    }
    
    HttpHeader getOutputHttpHeader() {
        return (!isLocallyInitiatedStream() ^ isUnidirectional()) ?
                spdyRequest.getResponse() :
                spdyRequest;
    }
    
    final Map<String, PushResource> getAssociatedResourcesToPush() {
        return associatedResourcesToPush;
    }
    
    /**
     * Add associated stream, so it might be closed when this stream is closed.
     */
    final void addAssociatedStream(final SpdyStream spdyStream)
            throws SpdyStreamException {
        if (associatedSpdyStreams == null) {
            associatedSpdyStreams = Collections.newSetFromMap(
                    DataStructures.<SpdyStream, Boolean>getConcurrentMap(8));
        }
        
        associatedSpdyStreams.add(spdyStream);
        
        if (!isOpen() && associatedSpdyStreams.remove(spdyStream)) {
            throw new SpdyStreamException(spdyStream.getStreamId(),
                    RstStreamFrame.REFUSED_STREAM, "The parent stream is closed");
        }
    }

    /**
     * Reset associated streams, when peer resets the parent stream.
     */
    final void rstAssociatedStreams() {
        if (associatedSpdyStreams != null) {
            for (Iterator<SpdyStream> it = associatedSpdyStreams.iterator(); it.hasNext(); ) {
                final SpdyStream associatedStream = it.next();
                it.remove();
                
                try {
                    associatedStream.resetRemotely();
                } catch (Exception ignored) {
                }
            }
        }
    }
    
    /**
     * Notify all close listeners
     */
    @SuppressWarnings("unchecked")
    private void notifyCloseListeners() {
        final CloseReason closeReason = closeReasonRef.get();
        
        CloseListener closeListener;
        while ((closeListener = closeListeners.poll()) != null) {
            try {
                closeListener.onClosed(this, closeReason.getType());
            } catch (IOException ignored) {
            }
        }
    }
    
    protected enum TerminationType {
        FIN, RST, LOCAL_CLOSE, PEER_CLOSE, FORCED
    }
    
    protected static class Termination {
        private final TerminationType type;
        private final String description;

        public Termination(final TerminationType type, final String description) {
            this.type = type;
            this.description = description;
        }

        public TerminationType getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }
        
        public void doTask() {
        }
    }
}