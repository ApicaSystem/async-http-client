/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.request.body;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.request.body.multipart.FilePart;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.File;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_FILE;
import static org.asynchttpclient.test.TestUtils.createTempFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FilePartLargeFileTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {

            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest req, HttpServletResponse resp) throws IOException {

                ServletInputStream in = req.getInputStream();
                byte[] b = new byte[8192];

                int count;
                int total = 0;
                while ((count = in.read(b)) != -1) {
                    b = new byte[8192];
                    total += count;
                }
                resp.setStatus(200);
                resp.addHeader("X-TRANSFERRED", String.valueOf(total));
                resp.getOutputStream().flush();
                resp.getOutputStream().close();

                baseRequest.setHandled(true);
            }
        };
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPutImageFile() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Response response = client.preparePut(getTargetUrl())
                    .addBodyPart(new FilePart("test", LARGE_IMAGE_FILE, "application/octet-stream", UTF_8))
                    .execute()
                    .get();
            assertEquals(200, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPutLargeTextFile() throws Exception {
        File file = createTempFile(1024 * 1024);

        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Response response = client.preparePut(getTargetUrl())
                    .addBodyPart(new FilePart("test", file, "application/octet-stream", UTF_8))
                    .execute()
                    .get();
            assertEquals(200, response.getStatusCode());
        }
    }
}
