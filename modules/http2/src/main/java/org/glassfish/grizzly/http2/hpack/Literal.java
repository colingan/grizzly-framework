/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved.
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
/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.glassfish.grizzly.http2.hpack;

import org.glassfish.grizzly.http2.compression.HeaderListener;
import java.io.IOException;
import java.io.OutputStream;
import org.glassfish.grizzly.Buffer;

import static org.glassfish.grizzly.http2.hpack.BinaryPrimitives.*;

//                  0   1   2   3   4   5   6   7
//                +---+---+---+---+---+---+---+---+
//                | 0 | 0 | 0 | 0 |  Index (4+)   |
//                +---+---+-----------------------+
//                | H |     Value Length (7+)     |
//                +---+---------------------------+
//                | Value String (Length octets)  |
//                +-------------------------------+
//
//                  0   1   2   3   4   5   6   7
//                +---+---+---+---+---+---+---+---+
//                | 0 | 0 | 0 | 0 |       0       |
//                +---+---+-----------------------+
//                | H |     Name Length (7+)      |
//                +---+---------------------------+
//                |  Name String (Length octets)  |
//                +---+---------------------------+
//                | H |     Value Length (7+)     |
//                +---+---------------------------+
//                | Value String (Length octets)  |
//                +-------------------------------+
//
public class Literal implements BinaryRepresentation {

    private static class InstanceHolder {
        private static final Literal INSTANCE = new Literal();
    }

    public static final Literal getInstance() {
        return InstanceHolder.INSTANCE;
    }
    
    private Literal() {
    }

    public static boolean matches(byte sig) {
        return (sig & 0b11110000) == 0;
    }

    @Override
    public void process(Buffer source, HeaderFieldTable.DecTable table,
                        HeaderListener handler) {

        ObjectHolder<String> s = new ObjectHolder<>();
        String name;
        int beginning = source.position();
        byte b = source.get();
        if ((b & 0b1111) == 0) {
            readString(source, s);
            name = s.getObj();
        } else {
            source.position(beginning);
            int index = readInteger(source, 7);
            HeaderField e = table.get(index);
            name = e.getName();
        }
        
        readString(source, s);
        String value = s.getObj();
        
        handler.onDecodedHeader(name, value);
    }

    public static void write(int index, String value, OutputStream destination, boolean useHuffman) throws IOException {
        writeInteger(destination, index, 4, 0);
        writeString(destination, value, useHuffman);
    }

    //
    // TODO: design defect: not enough freedom! we either write
    // all huffman or all plain :(
    //
    public static void write(String name, String value, OutputStream destination, boolean useHuffman) throws IOException {
        writeInteger(destination, 0, 4, 0);
        writeString(destination, name, useHuffman);
        writeString(destination, value, useHuffman);
    }
}
