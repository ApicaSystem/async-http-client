/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.asynchttpclient.channel;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MaxConnectionsInThreadsTest extends AbstractBasicTest {

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(new MockTimeoutHttpServlet()), "/timeout/*");

        server.start();
        port1 = connector.getLocalPort();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testMaxConnectionsWithinThreads() throws Exception {

        String[] urls = {getTargetUrl(), getTargetUrl()};

        AsyncHttpClientConfig config = config()
                .setConnectTimeout(1000)
                .setRequestTimeout(5000)
                .setKeepAlive(true)
                .setMaxConnections(1)
                .setMaxConnectionsPerHost(1)
                .build();

        final CountDownLatch inThreadsLatch = new CountDownLatch(2);
        final AtomicInteger failedCount = new AtomicInteger();

        try (AsyncHttpClient client = asyncHttpClient(config)) {
            for (final String url : urls) {
                Thread t = new Thread() {

                    @Override
                    public void run() {
                        client.prepareGet(url).execute(new AsyncCompletionHandlerBase() {
                            @Override
                            public Response onCompleted(Response response) throws Exception {
                                Response r = super.onCompleted(response);
                                inThreadsLatch.countDown();
                                return r;
                            }

                            @Override
                            public void onThrowable(Throwable t) {
                                super.onThrowable(t);
                                failedCount.incrementAndGet();
                                inThreadsLatch.countDown();
                            }
                        });
                    }
                };
                t.start();
            }

            inThreadsLatch.await();
            assertEquals(failedCount.get(), 1, "Max Connections should have been reached when launching from concurrent threads");

            final CountDownLatch notInThreadsLatch = new CountDownLatch(2);
            failedCount.set(0);

            for (final String url : urls) {
                client.prepareGet(url).execute(new AsyncCompletionHandlerBase() {
                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        Response r = super.onCompleted(response);
                        notInThreadsLatch.countDown();
                        return r;
                    }

                    @Override
                    public void onThrowable(Throwable t) {
                        super.onThrowable(t);
                        failedCount.incrementAndGet();
                        notInThreadsLatch.countDown();
                    }
                });
            }

            notInThreadsLatch.await();
            assertEquals(failedCount.get(), 1, "Max Connections should have been reached when launching from main thread");
        }
    }

    @Override
    public String getTargetUrl() {
        return "http://localhost:" + port1 + "/timeout/";
    }

    @SuppressWarnings("serial")
    public static class MockTimeoutHttpServlet extends HttpServlet {
        private static final Logger LOGGER = LoggerFactory.getLogger(MockTimeoutHttpServlet.class);
        private static final String contentType = "text/plain";
        private static final long DEFAULT_TIMEOUT = 2000;

        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setStatus(200);
            res.addHeader("Content-Type", contentType);

            long sleepTime;
            try {
                sleepTime = Integer.parseInt(req.getParameter("timeout"));
            } catch (NumberFormatException e) {
                sleepTime = DEFAULT_TIMEOUT;
            }

            try {
                LOGGER.debug("=======================================");
                LOGGER.debug("Servlet is sleeping for: " + sleepTime);
                LOGGER.debug("=======================================");
                Thread.sleep(sleepTime);
                LOGGER.debug("=======================================");
                LOGGER.debug("Servlet is awake for");
                LOGGER.debug("=======================================");
            } catch (Exception e) {
                //
            }

            res.setHeader("XXX", "TripleX");

            byte[] retVal = "1".getBytes();
            OutputStream os = res.getOutputStream();

            res.setContentLength(retVal.length);
            os.write(retVal);
            os.close();
        }
    }
}
