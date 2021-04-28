/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.protocol.hessian;

import com.caucho.hessian.client.HessianConnection;
import com.caucho.hessian.client.HessianConnectionFactory;
import com.caucho.hessian.client.HessianProxyFactory;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultClientConnectionReuseStrategy;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.remoting.Constants.DEFAULT_EXCHANGER;

/**
 * HttpClientConnectionFactory
 * TODO, Consider using connection pool
 * https://mp.weixin.qq.com/s/ocGqzAS0KaoF_pSZRVxwAA Http 持久连接与 HttpClient 连接池，有哪些不为人知的关系
 */
public class HttpClientConnectionFactory implements HessianConnectionFactory {

    private static Logger logger = LoggerFactory.getLogger(HttpClientConnectionFactory.class);
    private static ConcurrentLinkedQueue<PoolingHttpClientConnectionManager> managers=new ConcurrentLinkedQueue<>();
    private static ScheduledExecutorService monitorExecutor = Executors.newScheduledThreadPool(1,
            new NamedThreadFactory("hessian-connection-evict",true));

    private static final int MAX_CONN = 1000;    // 最大连接数，这里是一个url 对应一个 httpclient，所以MAX_CONN可以等于MAX_PRE_ROUTE
    private static final int MAX_PRE_ROUTE = 1000;// 路由最大连接数

    private static final int IDLE_TIME_SECONDS=30;  //清理空闲多久的连接
    private static final int EVICT_PERIOD_SECONDS=60;   //多久清理一次连接

    private CloseableHttpClient httpClient;

    static {
        monitorExecutor.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                managers.forEach(manager->{
                    //关闭异常连接
                    manager.closeExpiredConnections();
                    //关闭5s空闲的连接
                    manager.closeIdleConnections(IDLE_TIME_SECONDS, TimeUnit.SECONDS);
                });
            }
        },EVICT_PERIOD_SECONDS , EVICT_PERIOD_SECONDS, TimeUnit.SECONDS);
    }


    @Override
    public void setHessianProxyFactory(HessianProxyFactory factory) {

        ConnectionSocketFactory plainSocketFactory = PlainConnectionSocketFactory.getSocketFactory();
        LayeredConnectionSocketFactory sslSocketFactory = SSLConnectionSocketFactory.getSocketFactory();
        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory> create().register("http", plainSocketFactory)
                .register("https", sslSocketFactory).build();

        //连接池管理类
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager(registry);
        managers.add(manager);

        //设置连接参数
        manager.setMaxTotal(MAX_CONN); // 最大连接数
        manager.setDefaultMaxPerRoute(MAX_PRE_ROUTE); // 路由最大连接数
        SocketConfig socketConfig = SocketConfig.custom().setTcpNoDelay(true).build();
        manager.setDefaultSocketConfig(socketConfig);

        int connectTimeout= (int) factory.getConnectTimeout();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(connectTimeout)
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout((int) factory.getReadTimeout())
                .build();

        //请求失败时,进行请求重试
        HttpRequestRetryHandler handler = new HttpRequestRetryHandler() {
            @Override
            public boolean retryRequest(IOException e, int i, HttpContext httpContext) {
                if (i > 3){
                    //重试超过3次,放弃请求
                    logger.error("retry has more than 3 time, give up request");
                    return false;
                }
                if (e instanceof NoHttpResponseException){
                    //服务器没有响应,可能是服务器断开了连接,应该重试
                    logger.error("receive no response from server, retry");
                    return true;
                }
                if (e instanceof SSLHandshakeException){
                    // SSL握手异常
                    logger.error("SSL hand shake exception");
                    return false;
                }
                if (e instanceof InterruptedIOException){
                    //超时
                    logger.error("InterruptedIOException");
                    return false;
                }
                if (e instanceof UnknownHostException){
                    // 服务器不可达
                    logger.error("server host unknown");
                    return false;
                }
                if (e instanceof ConnectTimeoutException){
                    // 连接超时
                    logger.error("Connection Time out");
                    return false;
                }
                if (e instanceof SSLException){
                    logger.error("SSLException");
                    return false;
                }

                HttpClientContext context = HttpClientContext.adapt(httpContext);
                HttpRequest request = context.getRequest();
                if (!(request instanceof HttpEntityEnclosingRequest)){
                    //如果请求不是关闭连接的请求
                    return true;
                }
                return false;
            }
        };

        //HttpClient对象
        httpClient = HttpClients.custom().setConnectionManager(manager)
                .setRetryHandler(handler)
                .setConnectionReuseStrategy(DefaultClientConnectionReuseStrategy.INSTANCE)
                .setConnectionManagerShared(true)   //connection pool shut down https://blog.csdn.net/sinat_27143551/article/details/84257154  https://stackoverflow.com/questions/29488106/java-lang-illegalstateexception-connection-pool-shut-down-while-using-spring-re
                .setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())
              //.evictExpiredConnections().evictIdleConnections(30,TimeUnit.SECONDS) 这边不适合用内置的，因为httpclient很多个，不好每个都开一个线程进行回收
                .setDefaultRequestConfig(requestConfig).build();
    }

    @Override
    public HessianConnection open(URL url) {
        HttpClientConnection httpClientConnection = new HttpClientConnection(httpClient, url);
        RpcContext context = RpcContext.getContext();
        for (String key : context.getObjectAttachments().keySet()) {
            httpClientConnection.addHeader(DEFAULT_EXCHANGER + key, context.getAttachment(key));
        }
        return httpClientConnection;
    }
}
