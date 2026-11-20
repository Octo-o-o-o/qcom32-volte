package org.octolab.qcom32ims;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructTimeval;
import android.util.Log;
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
    private final StructPollfd socketPoll = new StructPollfd();
    private final StructPollfd wakePoll = new StructPollfd();
    private final StructPollfd[] pollFds = {socketPoll, wakePoll};
    private final byte[] wakeByte = {1};
    private ParcelFileDescriptor wakeRead;
    private ParcelFileDescriptor wakeWrite;
    private boolean wakePending;
    private boolean polling;
    private boolean timedReadFallback;

    @Override
    public void connect() throws IOException {
        socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED));
        socket.setSoTimeout(400);
        socketPoll.fd = socket.getFileDescriptor();
        try {
            // poll controls idle waits. Keep a separate timeout for incomplete frames.
            Os.setsockoptTimeval(socketPoll.fd, OsConstants.SOL_SOCKET,
                    OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(3000));
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            wakeRead = pipe[0];
            wakeWrite = pipe[1];
        } catch (ErrnoException | IOException ex) {
            timedReadFallback = true;
            Log.w("Qcom32VolteIms", "Using timed socket reads: " + ex.getClass().getSimpleName());
            return;
        }
        socketPoll.events = (short) OsConstants.POLLIN;
        wakePoll.fd = wakeRead.getFileDescriptor();
        wakePoll.events = (short) OsConstants.POLLIN;
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
    public byte[] readFrame(InputStream in, int timeoutMs) throws IOException {
        if (timedReadFallback) {
            return RilImsWire.receiveRawFrameOrNull(in, socket::setSoTimeout,
                    Math.max(1, Math.min(400, timeoutMs)), 3000);
        }
        return awaitInput(timeoutMs) ? RilImsWire.readFrame(in) : null;
    }

    private boolean awaitInput(int timeoutMs) throws IOException {
        synchronized (this) {
            if (wakeWrite == null) throw new IOException("IMS transport closed");
            polling = true;
        }
        try {
            if (Os.poll(pollFds, timeoutMs) == 0) return false;
            if (wakePoll.revents != 0) {
                synchronized (this) {
                    if (wakeWrite == null) throw new IOException("IMS transport closed");
                    if (wakePending) {
                        Os.read(wakePoll.fd, wakeByte, 0, 1);
                        wakePending = false;
                    }
                }
            }
            return socketPoll.revents != 0;
        } catch (ErrnoException ex) {
            throw new IOException("IMS reader poll failed", ex);
        } finally {
            synchronized (this) {
                polling = false;
                if (wakeWrite == null) closeWakeRead();
            }
        }
    }

    @Override
    public synchronized void wakeReader() throws IOException {
        // At most one byte sits in the pipe, so a binder thread never waits for pipe space.
        if (wakeWrite == null || wakePending) return;
        try {
            Os.write(wakeWrite.getFileDescriptor(), wakeByte, 0, 1);
            wakePending = true;
        } catch (ErrnoException ex) {
            throw new IOException("Could not wake IMS reader", ex);
        }
    }

    @Override
    public synchronized void close() {
        try {
            socket.shutdownInput();
        } catch (IOException ignored) { }
        try {
            socket.close();
        } catch (IOException ignored) { }
        if (wakeWrite != null) {
            // Closing the writer wakes poll even if close raced with entry into the syscall.
            try {
                wakeWrite.close();
            } catch (IOException ignored) { }
            wakeWrite = null;
        }
        if (!polling) closeWakeRead();
    }

    private void closeWakeRead() {
        if (wakeRead == null) return;
        try {
            wakeRead.close();
        } catch (IOException ignored) { }
        wakeRead = null;
    }
}
