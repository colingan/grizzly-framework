/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013-2015 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http2;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.glassfish.grizzly.ReadHandler;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.StaticHttpHandler;
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.junit.Test;

/**
 *
 * @author oleksiys
 */
public class TestMain {
    private static final int PORT = 7070;
    
    public static void main(String[] args) throws IOException {
        new TestMain().test();
    }
    
//    @Test
    public void test() {
        //BuffersBuffer.DEBUG_MODE = true;
        
        SSLContextConfigurator sslContextConfigurator = createSSLContextConfigurator();
        SSLEngineConfigurator serverSSLEngineConfigurator;

        if (sslContextConfigurator.validateConfiguration(true)) {
            serverSSLEngineConfigurator =
                    new SSLEngineConfigurator(sslContextConfigurator.createSSLContext(),
                    false, false, false);
            
//            serverSSLEngineConfigurator.setEnabledCipherSuites(new String[] {
//            });
        } else {
            throw new IllegalStateException("Failed to validate SSLContextConfiguration.");
        }
        
        HttpServer server = HttpServer.createSimpleServer("/tmp", PORT);
        NetworkListener listener = server.getListener("grizzly");
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        listener.setSendFileEnabled(false);
        
        listener.getFileCache().setEnabled(false);
        
        listener.setSecure(true);
        listener.setSSLEngineConfig(serverSSLEngineConfigurator);
        
        final Http2AddOn http2AddOn = new Http2AddOn();
        http2AddOn.setMaxConcurrentStreams(1024);
        listener.registerAddOn(http2AddOn);
        
        server.getServerConfiguration().addHttpHandler(
                new HttpHandler() {
                    final AtomicInteger ai = new AtomicInteger(1);
                    
                    @Override
                    public void service(Request request, Response response) throws Exception {
                        response.setContentType("text/plain");

                        final Writer w = response.getWriter();
                        w.write("Hello World #" + ai.getAndIncrement());
                    }
                }, "/text");
        
        server.getServerConfiguration().addHttpHandler(
                new HttpHandler() {
                    @Override
                    public void service(Request request, Response response) throws Exception {
                        response.setContentType("text/html");

                        final Writer w = response.getWriter();
                        StringBuilder sb = new StringBuilder(128);
                        sb.append("<html><head><title>HTTP2 Test</title></head><body>");
//                sb.append("Hello!<br />");
////                for (int i = 1; i <= 1000; i++) {
////                    sb.append("<img src=\"/").append(i).append(".jpg\" />");
////                }

                        sb.append("<form action=\"/post\" enctype=\"multipart/form-data\" method=\"post\">");
                        sb.append("<p> Type some text (if you like):<br>");
                        sb.append("<input type=\"text\" name=\"textline\" size=\"30\">");
                        sb.append("</p>");
                        sb.append("<p> Please specify a file, or a set of files:<br>");
                        sb.append("<input type=\"file\" name=\"datafile\" size=\"40\">");
                        sb.append("</p>");
                        sb.append("<div> <input type=\"submit\" value=\"Send\"> </div>");
                        sb.append("</form>");

                        sb.append("</body></html>");
                        response.setContentLength(sb.length());
                        w.write(sb.toString());
                    }
                }, "/upload");

        server.getServerConfiguration().addHttpHandler(
                new HttpHandler() {
                    @Override
                    public void service(Request request, final Response response) throws Exception {
                        final byte[] b = new byte[2048];
                        final NIOInputStream in = (NIOInputStream) request.getInputStream();
                        
                        System.out.println("content-length=" + request.getContentLength());
                        final long start = System.currentTimeMillis();
                        final FileOutputStream out = new FileOutputStream("/tmp/data.txt");
//                        final AtomicLong total = new AtomicLong();
//                        response.suspend();
//                        in.notifyAvailable(new ReadHandler() {
//                            @Override
//                            public void onDataAvailable() throws Exception {
//                                while (in.available() > 0) {
//                                    int readBytesCount = in.read(b);
//                                    out.write(b, 0, readBytesCount);
//                                    total.addAndGet(readBytesCount);
//                                }
//                                in.notifyAvailable(this);
//                            }
//
//                            @Override
//                            public void onError(Throwable t) {
//                                t.printStackTrace();
//                            }
//
//                            @Override
//                            public void onAllDataRead() throws Exception {
//                                while (in.available() > 0) {
//                                    int readBytesCount = in.read(b);
//                                    out.write(b, 0, readBytesCount);
//                                    total.addAndGet(readBytesCount);
//                                }
//                                out.flush();
//                                out.close();
//                                long stop = System.currentTimeMillis();
//                                System.out.println("All data read.  Total: " + total.get() + ", time: " + (stop - start) + "ms");
//                                response.setContentType("text/html");
//
//                                final Writer w = response.getWriter();
//                                StringBuilder sb = new StringBuilder(128);
//                                sb.append("<html><head><title>HTTP2 Test</title></head><body>");
//                                sb.append("Uploaded ").append(total).append(" bytes<br />");
//                                sb.append("</body></html>");
//                                response.setContentLength(sb.length());
//                                w.write(sb.toString());
//                                response.resume();
//                            }
//                        });
                        int len = 0;
                        int total = 0;
                        try {
                            while ((len = in.read(b)) > 0) {
                                total += len;
                                out.write(b, 0, len);
                                System.out.println("just read " + len + " bytes. total=" + total);
                            }
                            System.out.println("end of input");
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            out.close();
                        }
                        
                        response.setContentType("text/html");

                        final Writer w = response.getWriter();
                        StringBuilder sb = new StringBuilder(128);
                        sb.append("<html><head><title>HTTP2 Test</title></head><body>");
                        sb.append("Uploaded ").append(total).append(" bytes<br />");
                        sb.append("</body></html>");
                        response.setContentLength(sb.length());
                        w.write(sb.toString());
                    }
                }, "/post");

        server.getServerConfiguration().addHttpHandler(
                new HttpHandler() {
                    @Override
                    public void service(Request request, Response response) throws Exception {
                        response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
                        response.setHeader(Header.Location, "http://localhost:" + PORT + request.getParameter("path"));
                    }
                }, "/redirect");

        server.getServerConfiguration().addHttpHandler(
                new HttpHandler() {

                    @Override
                    public void service(final Request request, final Response response) throws Exception {
                        final Http2Stream stream =
                                (Http2Stream) request.getAttribute(
                                        Http2Stream.HTTP2_STREAM_ATTRIBUTE);
                        
                        final PushResource.PushResourceBuilder pushResourceBuilder = PushResource.builder()
                        .contentType("image/jpeg")
                        .statusCode(200, "PUSH")
                        .source(Source.factory(stream).createFileSource("/Users/oleksiys/Downloads/Navcore.bak/helpme/medical/images/image002.jpg"));

                        stream.addPushResource(
                                "/my.jpg",
                                pushResourceBuilder.build());

                        response.setStatus(200, "DONE");
                        final Writer w = response.getWriter();
                        StringBuilder sb = new StringBuilder(128);
                        sb.append("<html><head><title>HTTP2 Test</title></head><body>");
                        sb.append("<img src=\"my.jpg\" />");
//                sb.append("Hello!<br />");
////                for (int i = 1; i <= 1000; i++) {
////                    sb.append("<img src=\"/").append(i).append(".jpg\" />");
////                }
                        sb.append("</body></html>");
                        w.write(sb.toString());
                    }
                }, "/push");
        
        server.getServerConfiguration().addHttpHandler(
                new StaticHttpHandler("/Users/oleksiys/Downloads"), "/download");
        
        try {
            server.start();
            System.out.println("Press any key to stop ...");
            //noinspection ResultOfMethodCallIgnored
            System.in.read();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            server.shutdownNow();
        }
    }
    
    // --------------------------------------------------------- Private Methods

    
    private static SSLContextConfigurator createSSLContextConfigurator() {
        SSLContextConfigurator sslContextConfigurator =
                new SSLContextConfigurator();
        ClassLoader cl = Http2BaseFilter.class.getClassLoader();
        // override system properties
        URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        if (cacertsUrl != null) {
            sslContextConfigurator.setTrustStoreFile(cacertsUrl.getFile());
            sslContextConfigurator.setTrustStorePass("changeit");
        }

        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        if (keystoreUrl != null) {
            sslContextConfigurator.setKeyStoreFile(keystoreUrl.getFile());
            sslContextConfigurator.setKeyStorePass("changeit");
        }

        return sslContextConfigurator;
    }
}
