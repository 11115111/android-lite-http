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

package com.litesuits.http.impl.httpURLConnection;

import com.litesuits.http.exception.HttpNetException;
import com.litesuits.http.exception.NetException;
import com.litesuits.http.log.HttpLog;
import com.litesuits.http.network.Network;
import com.litesuits.http.request.AbstractRequest;
import com.litesuits.http.request.param.HttpMethods;

import org.apache.http.NoHttpResponseException;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.conn.ConnectTimeoutException;

import android.content.Context;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLException;

/**
 * The default {@link HttpRequestRetryHandler} used by request executors.
 *
 * @since 4.0
 */
public class CustomHttpRequestRetryHandler {
    public static final String TAG = CustomHttpRequestRetryHandler.class.getSimpleName();
    private HashSet<Class<?>> exceptionWhitelist = new HashSet<Class<?>>();
    private HashSet<Class<?>> exceptionBlacklist = new HashSet<Class<?>>();

    public final int retrySleepTimeMS;

    private Map<HttpMethods, Boolean> idempotentMethods;

    /**
     * Whether or not methods that have successfully sent their request will be retried
     */
    private final boolean requestSentRetryEnabled;

    /**
     * Create the request retry handler using the specified IOException classes
     *
     * @param retrySleepTimeMS
     * @param requestSentRetryEnabled true if it's OK to retry requests that have been sent
     * @since 4.3
     */
    public CustomHttpRequestRetryHandler(int retrySleepTimeMS, boolean requestSentRetryEnabled) {
        super();
        this.retrySleepTimeMS = retrySleepTimeMS;
        this.requestSentRetryEnabled = requestSentRetryEnabled;
        exceptionWhitelist.add(NoHttpResponseException.class);
        exceptionWhitelist.add(SocketException.class);
        exceptionWhitelist.add(SocketTimeoutException.class);
        exceptionWhitelist.add(ConnectTimeoutException.class);

        exceptionBlacklist.add(UnknownHostException.class);
        exceptionBlacklist.add(FileNotFoundException.class);
        exceptionBlacklist.add(SSLException.class);
        exceptionBlacklist.add(ConnectException.class);

        initIdempotentMethods();
    }


    public boolean retryRequest(IOException exception, int retryCount, int maxRetries, Context appContext, AbstractRequest request) throws HttpNetException, InterruptedException {
        boolean retry = true;
        if (retryCount > maxRetries) {
            if (HttpLog.isPrint) {
                HttpLog.w(TAG, "retry count > max retry times..");
            }
            throw new HttpNetException(exception);
        } else if (isInList(exceptionBlacklist, exception)) {
            if (HttpLog.isPrint) {
                HttpLog.w(TAG, "exception in blacklist..");
            }
            retry = false;
        } else if (isInList(exceptionWhitelist, exception)) {
            if (HttpLog.isPrint) {
                HttpLog.w(TAG, "exception in whitelist..");
            }
            retry = true;
        }
        if (retry) {
            // 判断连接是否取消，非否幂等请求是否重试
            retry = retryRequest(request);
        }
        if (retry) {
            if (appContext != null) {
                if (Network.isConnected(appContext)) {
                    HttpLog.d(TAG, "Network isConnected, retry now");
                } else if (Network.isConnectedOrConnecting(appContext)) {
                    if (HttpLog.isPrint) {
                        HttpLog.v(TAG, "Network is Connected Or Connecting, wait for retey : "
                                + retrySleepTimeMS + " ms");
                    }
                    Thread.sleep(retrySleepTimeMS);
                } else {
                    HttpLog.d(TAG, "Without any Network , immediately cancel retry");
                    throw new HttpNetException(NetException.NetworkNotAvilable);
                }
            } else {
                if (HttpLog.isPrint) {
                    HttpLog.v(TAG, "app context is null..");
                    HttpLog.v(TAG, "wait for retry : " + retrySleepTimeMS + " ms");
                }
                Thread.sleep(retrySleepTimeMS);
            }
        }
        if (HttpLog.isPrint) {
            HttpLog.i(TAG, "retry: " + retry + " , retryCount: " + retryCount + " , exception: " + exception);
        }
        return retry;
    }

    protected boolean isInList(HashSet<Class<?>> list, Throwable error) {
        for (Class<?> aList : list) {
            if (aList.isInstance(error)) {
                return true;
            }
        }
        return false;
    }


    protected boolean retryRequest(AbstractRequest request) {

        if (handleAsIdempotent(request)) {
            // Retry if the request is considered idempotent
            return true;
        }
        if (this.requestSentRetryEnabled) {
            // Retry if the request has not been sent fully or
            // if it's OK to retry methods that have been sent
            return true;
        }
        return false;
    }

    /**
     * @return <code>true</code> if this handler will retry methods that have
     * successfully sent their request, <code>false</code> otherwise
     */
    public boolean isRequestSentRetryEnabled() {
        return requestSentRetryEnabled;
    }


    /**
     * Default constructor
     */
    private void initIdempotentMethods() {
        this.idempotentMethods = new ConcurrentHashMap<HttpMethods, Boolean>();
        this.idempotentMethods.put(HttpMethods.Get, Boolean.TRUE);
        this.idempotentMethods.put(HttpMethods.Head, Boolean.TRUE);
        this.idempotentMethods.put(HttpMethods.Put, Boolean.TRUE);
        this.idempotentMethods.put(HttpMethods.Delete, Boolean.TRUE);
        this.idempotentMethods.put(HttpMethods.Options, Boolean.TRUE);
        this.idempotentMethods.put(HttpMethods.Trace, Boolean.TRUE);
    }

    protected boolean handleAsIdempotent(final AbstractRequest request) {
        if (request == null) {
            return true;
        }
        final HttpMethods method = request.getMethod();
        final Boolean b = this.idempotentMethods.get(method);
        return b != null && b.booleanValue();
    }

}
