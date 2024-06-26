/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.request.body.multipart;

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.netty.request.body.BodyChunkedInput;
import org.asynchttpclient.request.body.RandomAccessBody;
import org.asynchttpclient.request.body.multipart.part.MultipartPart;
import org.asynchttpclient.request.body.multipart.part.MultipartState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.MiscUtils.closeSilently;

public class MultipartBody implements RandomAccessBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultipartBody.class);

    private final List<MultipartPart<? extends Part>> parts;
    private final String contentType;
    private final byte[] boundary;
    private final long contentLength;
    private int currentPartIndex;
    private boolean done;
    private final AtomicBoolean closed = new AtomicBoolean();

    public MultipartBody(List<MultipartPart<? extends Part>> parts, String contentType, byte[] boundary) {
        this.boundary = boundary;
        this.contentType = contentType;
        this.parts = assertNotNull(parts, "parts");
        contentLength = computeContentLength();
    }

    private long computeContentLength() {
        try {
            long total = 0;
            for (MultipartPart<? extends Part> part : parts) {
                long l = part.length();
                if (l < 0) {
                    return -1;
                }
                total += l;
            }
            return total;
        } catch (Exception e) {
            LOGGER.error("An exception occurred while getting the length of the parts", e);
            return 0L;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (MultipartPart<? extends Part> part : parts) {
                closeSilently(part);
            }
        }
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getBoundary() {
        return boundary;
    }

    // Regular Body API
    @Override
    public BodyState transferTo(ByteBuf target) throws IOException {
        if (done) {
            return BodyState.STOP;
        }

        while (target.isWritable() && !done) {
            MultipartPart<? extends Part> currentPart = parts.get(currentPartIndex);
            currentPart.transferTo(target);

            if (currentPart.getState() == MultipartState.DONE) {
                currentPartIndex++;
                if (currentPartIndex == parts.size()) {
                    done = true;
                }
            }
        }

        return BodyState.CONTINUE;
    }

    // RandomAccessBody API, suited for HTTP but not for HTTPS (zero-copy)
    @Override
    public long transferTo(WritableByteChannel target) throws IOException {
        if (done) {
            return -1L;
        }

        long transferred = 0L;
        boolean slowTarget = false;

        while (transferred < BodyChunkedInput.DEFAULT_CHUNK_SIZE && !done && !slowTarget) {
            MultipartPart<? extends Part> currentPart = parts.get(currentPartIndex);
            transferred += currentPart.transferTo(target);
            slowTarget = currentPart.isTargetSlow();

            if (currentPart.getState() == MultipartState.DONE) {
                currentPartIndex++;
                if (currentPartIndex == parts.size()) {
                    done = true;
                }
            }
        }

        return transferred;
    }
}
