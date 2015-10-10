/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014-2015 Oracle and/or its affiliates. All rights reserved.
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

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.http2.draft14.Http2Connection14;

/**
 * Supported HTTP2 draft versions enum
 * 
 * @author Alexey Stashok
 */
public enum DraftVersion {
    DRAFT_14(new SessionFactory() {

        @Override
        public Http2Connection create(final Connection<?> grizzlyConnection,
                final boolean isServer,
                final Http2BaseFilter handlerFilter) {
            return new Http2Connection14(grizzlyConnection, isServer, handlerFilter);
        }
    }, "14", 100, 65535);
    
    
    private final String version;
    private final String clearTextId;
    private final String tlsId;
    private final String toString;
    private final int defaultMaxConcurrentStreams;
    private final int defaultStreamWindowSize;
    
    private final SessionFactory factory;
    
    private DraftVersion(final SessionFactory factory, final String version,
            final int defaultMaxConcurrentStreams,
            final int defaultStreamWindowSize) {
        this.factory = factory;
        this.version = version;
        this.defaultMaxConcurrentStreams = defaultMaxConcurrentStreams;
        this.defaultStreamWindowSize = defaultStreamWindowSize;
        
        this.clearTextId = "h2c-" + version;
        this.tlsId = "h2-" + version;
        toString = "[HTTP/2 draft #" + version + "]";
    }

    public Http2Connection newConnection(final Connection<?> connection,
            final boolean isServer,
            final Http2BaseFilter handlerFilter) {
        return factory.create(connection, isServer, handlerFilter);
    }
    
    @Override
    public String toString() {
        return toString;
    }

    public String getVersion() {
        return version;
    }
    
    public String getClearTextId() {
        return clearTextId;
    }

    public String getTlsId() {
        return tlsId;
    }
    
    public int getDefaultMaxConcurrentStreams() {
        return defaultMaxConcurrentStreams;
    }
    
    public int getDefaultStreamWindowSize() {
        return defaultStreamWindowSize;
    }
    
    public boolean equals(final String version) {
        return clearTextId.equalsIgnoreCase(version) ||
                tlsId.equalsIgnoreCase(version);
    }
    
    public static DraftVersion fromString(final String version) {
        if (DRAFT_14.equals(version)) {
            return DRAFT_14;
        }
        
        return null;
    }
    
    private interface SessionFactory {

        public Http2Connection create(final Connection<?> connection,
                final boolean isServer,
                final Http2BaseFilter handlerFilter);
    }
}
