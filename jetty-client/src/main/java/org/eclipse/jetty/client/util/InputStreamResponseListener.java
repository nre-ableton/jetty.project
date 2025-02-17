//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Response.Listener;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link Listener} that produces an {@link InputStream}
 * that allows applications to read the response content.
 * <p>
 * Typical usage is:
 * <pre>
 * InputStreamResponseListener listener = new InputStreamResponseListener();
 * client.newRequest(...).send(listener);
 *
 * // Wait for the response headers to arrive
 * Response response = listener.get(5, TimeUnit.SECONDS);
 * if (response.getStatus() == 200)
 * {
 *     // Obtain the input stream on the response content
 *     try (InputStream input = listener.getInputStream())
 *     {
 *         // Read the response content
 *     }
 * }
 * </pre>
 * <p>
 * The {@link HttpClient} implementation (the producer) will feed the input stream
 * asynchronously while the application (the consumer) is reading from it.
 * <p>
 * If the consumer is faster than the producer, then the consumer will block
 * with the typical {@link InputStream#read()} semantic.
 * If the consumer is slower than the producer, then the producer will block
 * until the client consumes.
 */
public class InputStreamResponseListener extends Listener.Adapter
{
    private static final Logger LOG = LoggerFactory.getLogger(InputStreamResponseListener.class);
    private static final Chunk EOF = new Chunk(BufferUtil.EMPTY_BUFFER, Callback.NOOP);

    private final AutoLock.WithCondition lock = new AutoLock.WithCondition();
    private final CountDownLatch responseLatch = new CountDownLatch(1);
    private final CountDownLatch resultLatch = new CountDownLatch(1);
    private final AtomicReference<InputStream> stream = new AtomicReference<>();
    private final Queue<Chunk> chunks = new ArrayDeque<>();
    private Response response;
    private Result result;
    private Throwable failure;
    private boolean closed;

    public InputStreamResponseListener()
    {
    }

    @Override
    public void onHeaders(Response response)
    {
        try (AutoLock ignored = lock.lock())
        {
            this.response = response;
            responseLatch.countDown();
        }
    }

    @Override
    public void onContent(Response response, ByteBuffer content, Callback callback)
    {
        if (content.remaining() == 0)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Skipped empty content {}", content);
            callback.succeeded();
            return;
        }

        boolean closed;
        try (AutoLock.WithCondition l = lock.lock())
        {
            closed = this.closed;
            if (!closed)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Queueing content {}", content);
                chunks.add(new Chunk(content, callback));
                l.signalAll();
            }
        }

        if (closed)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("InputStream closed, ignored content {}", content);
            callback.failed(new AsynchronousCloseException());
        }
    }

    @Override
    public void onSuccess(Response response)
    {
        try (AutoLock.WithCondition l = lock.lock())
        {
            if (!closed)
                chunks.add(EOF);
            l.signalAll();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("End of content");
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        List<Callback> callbacks;
        try (AutoLock.WithCondition l = lock.lock())
        {
            if (this.failure != null)
                return;
            this.failure = failure;
            callbacks = drain();
            l.signalAll();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Content failure", failure);

        callbacks.forEach(callback -> callback.failed(failure));
    }

    @Override
    public void onComplete(Result result)
    {
        Throwable failure = result.getFailure();
        List<Callback> callbacks = Collections.emptyList();
        try (AutoLock.WithCondition l = lock.lock())
        {
            this.result = result;
            if (result.isFailed() && this.failure == null)
            {
                this.failure = failure;
                callbacks = drain();
            }
            // Notify the response latch in case of request failures.
            responseLatch.countDown();
            resultLatch.countDown();
            l.signalAll();
        }

        if (LOG.isDebugEnabled())
        {
            if (failure == null)
                LOG.debug("Result success");
            else
                LOG.debug("Result failure", failure);
        }

        callbacks.forEach(callback -> callback.failed(failure));
    }

    /**
     * Waits for the given timeout for the response to be available, then returns it.
     * <p>
     * The wait ends as soon as all the HTTP headers have been received, without waiting for the content.
     * To wait for the whole content, see {@link #await(long, TimeUnit)}.
     *
     * @param timeout the time to wait
     * @param unit the timeout unit
     * @return the response
     * @throws InterruptedException if the thread is interrupted
     * @throws TimeoutException if the timeout expires
     * @throws ExecutionException if a failure happened
     */
    public Response get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException, ExecutionException
    {
        boolean expired = !responseLatch.await(timeout, unit);
        if (expired)
            throw new TimeoutException();
        try (AutoLock ignored = lock.lock())
        {
            // If the request failed there is no response.
            if (response == null)
                throw new ExecutionException(failure);
            return response;
        }
    }

    /**
     * Waits for the given timeout for the whole request/response cycle to be finished,
     * then returns the corresponding result.
     * <p>
     *
     * @param timeout the time to wait
     * @param unit the timeout unit
     * @return the result
     * @throws InterruptedException if the thread is interrupted
     * @throws TimeoutException if the timeout expires
     * @see #get(long, TimeUnit)
     */
    public Result await(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException
    {
        boolean expired = !resultLatch.await(timeout, unit);
        if (expired)
            throw new TimeoutException();
        try (AutoLock ignored = lock.lock())
        {
            return result;
        }
    }

    /**
     * Returns an {@link InputStream} providing the response content bytes.
     * <p>
     * The method may be invoked only once; subsequent invocations will return a closed {@link InputStream}.
     *
     * @return an input stream providing the response content
     */
    public InputStream getInputStream()
    {
        InputStream result = new Input();
        if (stream.compareAndSet(null, result))
            return result;
        return IO.getClosedStream();
    }

    private List<Callback> drain()
    {
        List<Callback> callbacks = new ArrayList<>();
        try (AutoLock ignored = lock.lock())
        {
            while (true)
            {
                Chunk chunk = chunks.peek();
                if (chunk == null || chunk == EOF)
                    break;
                callbacks.add(chunk.callback);
                chunks.poll();
            }
        }
        return callbacks;
    }

    private class Input extends InputStream
    {
        @Override
        public int read() throws IOException
        {
            byte[] tmp = new byte[1];
            int read = read(tmp);
            if (read < 0)
                return read;
            return tmp[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int offset, int length) throws IOException
        {
            try
            {
                int result;
                Callback callback = null;
                try (AutoLock.WithCondition l = lock.lock())
                {
                    Chunk chunk;
                    while (true)
                    {
                        chunk = chunks.peek();
                        if (chunk == EOF)
                            return -1;

                        if (chunk != null)
                            break;

                        if (failure != null)
                            throw toIOException(failure);

                        if (closed)
                            throw new AsynchronousCloseException();

                        l.await();
                    }

                    ByteBuffer buffer = chunk.buffer;
                    result = Math.min(buffer.remaining(), length);
                    buffer.get(b, offset, result);
                    if (!buffer.hasRemaining())
                    {
                        callback = chunk.callback;
                        chunks.poll();
                    }
                }
                if (callback != null)
                    callback.succeeded();
                return result;
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
        }

        private IOException toIOException(Throwable failure)
        {
            if (failure instanceof IOException)
                return (IOException)failure;
            else
                return new IOException(failure);
        }

        @Override
        public void close() throws IOException
        {
            List<Callback> callbacks;
            try (AutoLock.WithCondition l = lock.lock())
            {
                if (closed)
                    return;
                closed = true;
                callbacks = drain();
                l.signalAll();
            }

            if (LOG.isDebugEnabled())
                LOG.debug("InputStream close");

            Throwable failure = new AsynchronousCloseException();
            callbacks.forEach(callback -> callback.failed(failure));

            super.close();
        }
    }

    private static class Chunk
    {
        private final ByteBuffer buffer;
        private final Callback callback;

        private Chunk(ByteBuffer buffer, Callback callback)
        {
            this.buffer = Objects.requireNonNull(buffer);
            this.callback = Objects.requireNonNull(callback);
        }
    }
}
