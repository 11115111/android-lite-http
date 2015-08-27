package com.litesuits.http.impl.httpURLConnection;

import com.litesuits.http.HttpConfig;
import com.litesuits.http.LiteHttp;
import com.litesuits.http.data.Charsets;
import com.litesuits.http.data.Consts;
import com.litesuits.http.data.HttpStatus;
import com.litesuits.http.exception.HttpClientException;
import com.litesuits.http.exception.HttpNetException;
import com.litesuits.http.exception.HttpServerException;
import com.litesuits.http.exception.ServerException;
import com.litesuits.http.listener.HttpListener;
import com.litesuits.http.listener.StatisticsListener;
import com.litesuits.http.log.HttpLog;
import com.litesuits.http.parser.DataParser;
import com.litesuits.http.request.AbstractRequest;
import com.litesuits.http.request.content.ByteArrayBody;
import com.litesuits.http.request.content.FileBody;
import com.litesuits.http.request.content.HttpBody;
import com.litesuits.http.request.content.InputStreamBody;
import com.litesuits.http.request.content.StringBody;
import com.litesuits.http.request.content.multi.BytesPart;
import com.litesuits.http.request.content.multi.FilePart;
import com.litesuits.http.request.content.multi.InputStreamPart;
import com.litesuits.http.request.content.multi.MultipartBody;
import com.litesuits.http.request.content.multi.StringPart;
import com.litesuits.http.request.param.HttpMethods;
import com.litesuits.http.response.InternalResponse;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;

import android.annotation.TargetApi;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Implementing with HttpURLConnection
 * @author PanBC
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class HurlConn extends LiteHttp {
    private static String TAG = HurlConn.class.getSimpleName();
    private CustomHttpRequestRetryHandler retryHandler;
    private int mConnectTimeout;
    private int mReadTimeout;

    public HurlConn(HttpConfig config) {
        super();
        initConfig(config);
    }

    @Override
    public void initConfig(HttpConfig config) {
        if (config == null) {
            config = new HttpConfig(null);
        }
        super.initConfig(config);
        retryHandler = new CustomHttpRequestRetryHandler(config.getRetrySleepMillis(), config.isRequestSentRetryEnabled());
        mConnectTimeout = config.getConnectTimeout();
        mReadTimeout = config.getSocketTimeout();
    }


    /*__________________________ implemention_methods __________________________*/
    protected URL initUrl(AbstractRequest request) throws HttpClientException, MalformedURLException {
        switch (request.getMethod()) {
            case Get:
            case Head:
            case Delete:
            case Trace:
            case Options:
                return new URL(request.getFullUri());
            case Post:
            case Put:
            case Patch:
                return new URL(request.getUri());
            default:
                return new URL(request.getFullUri());

        }
    }

    /**
     * deal with hard work
     * @param connection
     * @param request
     * @throws IOException
     */
    private void processMore(HttpURLConnection connection, AbstractRequest request) throws IOException {
        switch (request.getMethod()) {
            case Get:
            case Head:
            case Delete:
            case Trace:
            case Options:
                return;
        }

        HttpBody body = request.getHttpBody();
        if (body != null) {
            MultipartBody mulTiBody = new MultipartBody();
            if (body instanceof MultipartBody) {
                mulTiBody = (MultipartBody) body;
            } else if (body instanceof StringBody) {
                StringBody b = (StringBody) body;
                mulTiBody.addPart(new StringPart("string", b.string, b.charset, null));
            } else if (body instanceof ByteArrayBody) {
                // ByteArrayBody SerializableBody
                ByteArrayBody b = (ByteArrayBody) body;
                mulTiBody.addPart(new BytesPart("byteArray", b.bytes));
            } else if (body instanceof InputStreamBody) {
                InputStreamBody b = (InputStreamBody) body;
                mulTiBody.addPart(new InputStreamPart("inputstream", b.inputStream, "inputstream", b.getContentType()));
            } else if (body instanceof FileBody) {
                FileBody b = (FileBody) body;
                mulTiBody.addPart(new FilePart("file", b.file, b.getContentType()));
            } else {
                throw new RuntimeException("Unpredictable Entity Body(非法实体)");
            }
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod(request.getMethod().toString());
            connection.setRequestProperty("Content-Type", mulTiBody.getContentType());
            OutputStream outputStream = connection.getOutputStream();
            mulTiBody.writeTo(outputStream);
            outputStream.flush();
            outputStream.close();

        }
    }


    /**
     * 连接网络读取数据
     */
    @Override
    protected <T> void connectWithRetries(AbstractRequest<T> request, InternalResponse response)
            throws HttpClientException, HttpNetException, HttpServerException {

        HttpURLConnection connection = null;
        boolean retry = true;
        IOException cause = null;
        int times = 0, maxRetryTimes = request.getMaxRetryTimes(), maxRedirectTimes = request.getMaxRedirectTimes();
        HttpListener<T> listener = request.getHttpListener();
        StatisticsListener statistic = response.getStatistics();
        while (retry) {
            try {
                if (statistic != null) {
                    statistic.onPreConnect(request);
                }
                //  try to connect
                connection = (HttpURLConnection) initUrl(request).openConnection();
                connection.setConnectTimeout(mConnectTimeout);
                connection.setReadTimeout(mReadTimeout);

                // update http header
                if (request.getHeaders() != null) {
                    Set<Entry<String, String>> set = request.getHeaders().entrySet();
                    for (Entry<String, String> en : set) {
                        connection.addRequestProperty(en.getKey(), en.getValue());
                    }
                }
                // deal with hard work
                processMore(connection, request);


                if (request.isCancelledOrInterrupted()) {
                    return;
                }

                //codes blew will wrap responses from HttpURLConnection with apache-version HttpResponse
                //for convenience
                ProtocolVersion protocolVersion = new ProtocolVersion("HTTP", 1, 1);
                int responseCode = connection.getResponseCode();
                if (responseCode == -1) {
                    // -1 is returned by getResponseCode() if the response code could not be retrieved.
                    // Signal to the caller that something was wrong with the connection.
                    throw new IOException("Could not retrieve response code from HttpUrlConnection.");
                }
                StatusLine responseStatus = new BasicStatusLine(protocolVersion,
                        connection.getResponseCode(), connection.getResponseMessage());
                HttpResponse basicHttpResponse = new BasicHttpResponse(responseStatus);
                if (hasResponseBody(request.getMethod(), responseStatus.getStatusCode())) {
                    basicHttpResponse.setEntity(entityFromConnection(connection));
                }
                for (Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() != null) {
                        Header h = new BasicHeader(header.getKey(), header.getValue().get(0));
                        basicHttpResponse.addHeader(h);
                    }
                }
                if (statistic != null) {
                    statistic.onAfterConnect(request);
                }
                // status
                StatusLine status = basicHttpResponse.getStatusLine();
                HttpStatus httpStatus = new HttpStatus(status.getStatusCode(), status.getReasonPhrase());
                response.setHttpStatus(httpStatus);
                // header
                Header[] headers = basicHttpResponse.getAllHeaders();
                if (headers != null) {
                    com.litesuits.http.data.NameValuePair hs[] = new com.litesuits.http.data.NameValuePair[headers.length];
                    for (int i = 0; i < headers.length; i++) {
                        String name = headers[i].getName();
                        String value = headers[i].getValue();
                        if ("Content-Length".equalsIgnoreCase(name)) {
                            response.setContentLength(Long.parseLong(value));
                        }
                        hs[i] = new com.litesuits.http.data.NameValuePair(name, value);
                    }
                    response.setHeaders(hs);
                }

                // data body
                if (status.getStatusCode() <= 299 || status.getStatusCode() == 600) {
                    // 成功
                    HttpEntity entity = basicHttpResponse.getEntity();
                    if (entity != null) {
                        // charset
                        String charSet = getCharsetFromEntity(entity, request.getCharSet());
                        response.setCharSet(charSet);
                        // is cancelled ?
                        if (request.isCancelledOrInterrupted()) {
                            return;
                        }
                        // length
                        long len = response.getContentLength();
                        DataParser<T> parser = request.getDataParser();
                        if (statistic != null) {
                            statistic.onPreRead(request);
                        }
                        parser.readFromNetStream(entity.getContent(), len, charSet, config.getCacheDirPath());
                        if (statistic != null) {
                            statistic.onAfterRead(request);
                        }
                        response.setReadedLength(parser.getReadedLength());
                    }
                    return;
                } else if (status.getStatusCode() <= 399) {
                    // redirect
                    if (response.getRedirectTimes() < maxRedirectTimes) {
                        // get the location header to find out where to redirect to
                        Header locationHeader = basicHttpResponse.getFirstHeader(Consts.REDIRECT_LOCATION);
                        if (locationHeader != null) {
                            String location = locationHeader.getValue();
                            if (location != null && location.length() > 0) {
                                if (!location.toLowerCase().startsWith("http")) {
                                    URI uri = new URI(request.getFullUri());
                                    URI redirect = new URI(uri.getScheme(), uri.getHost(), location, null);
                                    location = redirect.toString();
                                }
                                response.setRedirectTimes(response.getRedirectTimes() + 1);
                                request.setUri(location);
                                if (HttpLog.isPrint) {
                                    HttpLog.i(TAG, "Redirect to : " + location);
                                }
                                if (listener != null) {
                                    listener.notifyCallRedirect(request, maxRedirectTimes, response.getRedirectTimes());
                                }
                                connectWithRetries(request, response);
                                return;
                            }
                        }
                        throw new HttpServerException(httpStatus);
                    } else {
                        throw new HttpServerException(ServerException.RedirectTooMuch);
                    }
                } else if (status.getStatusCode() <= 499) {
                    // 客户端被拒
                    throw new HttpServerException(httpStatus);
                } else if (status.getStatusCode() < 599) {
                    // 服务器有误
                    throw new HttpServerException(httpStatus);
                }
            } catch (IOException e) {
                cause = e;
            } catch (NullPointerException e) {
                // bug in HttpClient 4.0.x, see http://code.google.com/p/android/issues/detail?id=5255
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD) {
                    cause = new IOException(e.getMessage());
                } else {
                    cause = new IOException(e);
                }
            } catch (URISyntaxException e) {
                throw new HttpClientException(e);
            } catch (RuntimeException e) {
                throw new HttpClientException(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            if (cause != null) {
                try {
                    if (request.isCancelledOrInterrupted()) {
                        return;
                    }
                    times++;
                    retry = retryHandler.retryRequest(cause, times, maxRetryTimes, config.getContext(), request);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    return;
                }
                if (retry) {
                    response.setRetryTimes(times);
                    if (HttpLog.isPrint) {
                        HttpLog.i(TAG, "LiteHttp retry request: " + request.getUri());
                    }
                    if (listener != null) {
                        listener.notifyCallRetry(request, maxRetryTimes, times);
                    }
                }
            }
        }
        if (cause != null) {
            throw new HttpNetException(cause);
        }
    }

    private static boolean hasResponseBody(HttpMethods requestMethod, int responseCode) {
        return requestMethod != HttpMethods.Head
                && !(org.apache.http.HttpStatus.SC_CONTINUE <= responseCode && responseCode < org.apache.http.HttpStatus.SC_OK)
                && responseCode != org.apache.http.HttpStatus.SC_NO_CONTENT
                && responseCode != org.apache.http.HttpStatus.SC_NOT_MODIFIED;
    }

    private static HttpEntity entityFromConnection(HttpURLConnection connection) {
        BasicHttpEntity entity = new BasicHttpEntity();
        InputStream inputStream;
        try {
            inputStream = connection.getInputStream();
        } catch (IOException ioe) {
            inputStream = connection.getErrorStream();
        }
        entity.setContent(inputStream);
        entity.setContentLength(connection.getContentLength());
        entity.setContentEncoding(connection.getContentEncoding());
        entity.setContentType(connection.getContentType());
        return entity;
    }

    /**
     * get Charset String From HTTP Response
     */
    private String getCharsetFromEntity(HttpEntity entity, String defCharset) {
        final Header header = entity.getContentType();
        if (header != null) {
            final HeaderElement[] elements = header.getElements();
            if (elements.length > 0) {
                HeaderElement helem = elements[0];
                // final String mimeType = helem.getName();
                final NameValuePair[] params = helem.getParameters();
                if (params != null) {
                    for (final NameValuePair param : params) {
                        if (param.getName().equalsIgnoreCase("charset")) {
                            String s = param.getValue();
                            if (s != null && s.length() > 0) {
                                return s;
                            }
                        }
                    }
                }
            }
        }
        return defCharset == null ? Charsets.UTF_8 : defCharset;
    }


}
