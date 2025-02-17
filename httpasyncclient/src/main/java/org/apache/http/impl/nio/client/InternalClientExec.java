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
package org.apache.http.impl.nio.client;

import java.io.IOException;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;

interface InternalClientExec {

    void prepare(
            HttpHost target,
            HttpRequest original,
            InternalState state,
            AbstractClientExchangeHandler handler) throws IOException, HttpException;

    HttpRequest generateRequest(
            InternalState state,
            AbstractClientExchangeHandler handler) throws IOException, HttpException;

    void produceContent(
            InternalState state,
            ContentEncoder encoder,
            IOControl ioControl) throws IOException;

    void requestCompleted(
            InternalState state,
            AbstractClientExchangeHandler handler);

    void responseReceived(
            HttpResponse response,
            InternalState state,
            AbstractClientExchangeHandler handler) throws IOException, HttpException;

    void consumeContent(
            InternalState state,
            ContentDecoder decoder,
            IOControl ioControl) throws IOException;

    void responseCompleted(
            InternalState state,
            AbstractClientExchangeHandler handler) throws IOException, HttpException;

}
