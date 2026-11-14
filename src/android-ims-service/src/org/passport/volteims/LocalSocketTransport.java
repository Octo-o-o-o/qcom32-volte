package org.passport.volteims;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Android LocalSocket transport connecting to /dev/socket/qmux_radio/rild_ims0
 * in the RESERVED socket namespace. A fresh instance is created per connection attempt.
 */
final class LocalSocketTransport implements RilImsCoreClient.SocketTransport {
    private static final String SOCKET_NAME = "qmux_radio/rild_ims0";
    private final LocalSocket socket = new LocalSocket();

    @Override
    public void connect() throws IOException {
        socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED));
        socket.setSoTimeout(1500);
    }

    @Override
    public int peerUid() throws IOException {
        return socket.getPeerCredentials().getUid();
    }

    @Override
    public int peerPid() throws IOException {
        return socket.getPeerCredentials().getPid();
    }

    @Override
    public InputStream inputStream() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream outputStream() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void setSoTimeout(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) { }
    }
}
