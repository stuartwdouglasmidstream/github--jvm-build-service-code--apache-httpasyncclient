/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.nio.client.methods;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentDecoderChannel;
import org.apache.http.nio.FileContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Asserts;

/**
 * {@link org.apache.http.nio.protocol.HttpAsyncResponseConsumer} implementation that
 * streams content entity enclosed in an HTTP response directly into a file
 * without an intermediate in-memory buffer.
 * <p>
 * This consumer can be useful for file downloads.
 *
 * @since 4.0
 */
public abstract class ZeroCopyConsumer<T> extends AbstractAsyncResponseConsumer<T> {

    private final File file;
    private final RandomAccessFile accessfile;

    private HttpResponse response;
    private ContentType contentType;
    private Header contentEncoding;
    private FileChannel fileChannel;
    private long idx = -1;

    public ZeroCopyConsumer(final File file) throws FileNotFoundException {
        super();
        if (file == null) {
            throw new IllegalArgumentException("File may nor be null");
        }
        this.file = file;
        this.accessfile = new RandomAccessFile(this.file, "rw");
    }

    @Override
    protected void onResponseReceived(final HttpResponse response) {
        this.response = response;
    }

    @Override
    protected void onEntityEnclosed(
            final HttpEntity entity, final ContentType contentType) throws IOException {
        this.contentType = contentType;
        this.contentEncoding = entity.getContentEncoding();
        this.fileChannel = this.accessfile.getChannel();
        this.idx = 0;
    }

    @Override
    protected void onContentReceived(
            final ContentDecoder decoder, final IOControl ioControl) throws IOException {
        Asserts.notNull(this.fileChannel, "File channel");
        final long transferred;
        if (decoder instanceof FileContentDecoder) {
            transferred = ((FileContentDecoder)decoder).transfer(
                    this.fileChannel, this.idx, Integer.MAX_VALUE);
        } else {
            transferred = this.fileChannel.transferFrom(
                    new ContentDecoderChannel(decoder), this.idx, Integer.MAX_VALUE);
        }
        if (transferred > 0) {
            this.idx += transferred;
        }
        if (decoder.isCompleted()) {
            this.fileChannel.close();
        }
    }

    /**
     * Invoked to process received file.
     *
     * @param response original response head.
     * @param file file containing response content.
     * @param contentType the cotnent type.
     * @return result of the response processing
     */
    protected abstract T process(
            HttpResponse response, File file, ContentType contentType) throws Exception;

    @Override
    protected T buildResult(final HttpContext context) throws Exception {
        final FileEntity entity = new FileEntity(this.file, this.contentType);
        entity.setContentEncoding(this.contentEncoding);
        this.response.setEntity(entity);
        return process(this.response, this.file, this.contentType);
    }

    @Override
    protected void releaseResources() {
        try {
            this.accessfile.close();
        } catch (final IOException ignore) {
        }
    }

}
