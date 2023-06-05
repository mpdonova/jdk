/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

import javax.net.ssl.*;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Security;
import java.util.List;

/*
 * @test id=Server
 * @bug 8301379
 * @summary Verify that Java will not negotiate disabled cipher suites when the
 * other side of the connection requests them.
 *
 * @library /javax/net/ssl/templates
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_RSA_WITH_AES_256_CBC_SHA
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server true TLS_ECDH_RSA_WITH_AES_128_CBC_SHA
 */

/*
 * @test id=Client
 * @bug 8301379
 * @summary Verify that Java will not negotiate disabled cipher suites when the
 * other side of the connection requests them.
 *
 * @library /javax/net/ssl/templates
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_RSA_WITH_AES_256_CBC_SHA
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA
 * @run main/othervm TLSWontNegotiateDisabledCipherAlgos server false TLS_ECDH_RSA_WITH_AES_128_CBC_SHA
 */


public class TLSWontNegotiateDisabledCipherAlgos {
    public static void main(String [] args) throws Exception {
        boolean useDisabledAlgo = Boolean.parseBoolean(args[1]);
        if (useDisabledAlgo) {
            Security.setProperty("jdk.tls.disabledAlgorithms", "");
        }

        if (args[0].equals("server")) {
            try(TLSServer server = new TLSServer(useDisabledAlgo)) {
                List<String> command = List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "TLSWontNegotiateDisabledCipherAlgos",
                        "client",
                        Boolean.toString(!useDisabledAlgo),
                        Integer.toString(server.getListeningPort())
                );
                ProcessBuilder builder = new ProcessBuilder(command);
                Process p = builder.inheritIO().start();
                server.run();
                p.destroy();
            }
        } else if (args[0].equals("client")) {
            try(TLSClient client = new TLSClient(Integer.parseInt(args[2]), useDisabledAlgo)) {
                client.run();
            }
        }
    }

    private static class TLSClient extends SSLContextTemplate implements AutoCloseable {
        private final int portNumber;
        private final SSLSocket socket;

        public TLSClient(int portNumber, boolean useDisableAlgo) throws Exception {
            this.portNumber = portNumber;
            SSLContext context = createClientSSLContext();
            socket = (SSLSocket)context.getSocketFactory().createSocket("localhost", portNumber);
            if (useDisableAlgo) {
                socket.setEnabledCipherSuites(new String[]{"TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384"});
            }
        }

        public void run() throws IOException {
            try {
                socket.getOutputStream().write("SECRET MESSAGE".getBytes(StandardCharsets.UTF_8));
            } catch (SSLHandshakeException exc) {
                // handshake failures are expected
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    private static class TLSServer extends SSLContextTemplate implements AutoCloseable {
        private SSLServerSocket serverSocket;

        public TLSServer(boolean useDisableAlgo) throws Exception {
            SSLContext ctx = createServerSSLContext();
            serverSocket = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0);
            if (useDisableAlgo) {
                serverSocket.setEnabledCipherSuites(new String[]{"TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384"});
            }
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
        }

        public int getListeningPort() {
            return serverSocket.getLocalPort();
        }

        public void run() throws IOException {
            try(Socket clientSocket = serverSocket.accept()) {
                try {
                    byte[] bytes = clientSocket.getInputStream().readAllBytes();
                    throw new RuntimeException("The expected SSLHandshakeException was not thrown.");
                } catch (SSLHandshakeException exc) {
                    if (!exc.getMessage().contains("no cipher suites in common")) {
                        throw exc;
                    } else {
                        System.out.println("Success: The connection could not be negotiated (as expected.)");
                    }
                }
            }
        }
    }
}
