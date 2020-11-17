package ru.mail.polis.service.kovalkov.ranges;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;
import ru.mail.polis.dao.kovalkov.utils.BufferConverter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;

import static java.util.Objects.*;

public class StreamingSession extends HttpSession {
    private Iterator<Record> dataIterator;
    private static final byte[] CRLF = "\r\n".getBytes(Charset.defaultCharset());
    private static final byte[] SPLIT = "\n".getBytes(Charset.defaultCharset());
    private static final byte[] END_STREAM = "0\r\n\r\n".getBytes(Charset.defaultCharset());

    public StreamingSession(@NotNull final Socket socket, @NotNull final HttpServer server) {
        super(socket, server);
    }

    public void setDataIterator(final Iterator<Record> dataIterator) throws IOException {
        this.dataIterator = dataIterator;
        final var response = new Response(Response.OK);
        response.addHeader("Transfer-Encoding: chunked");
        writeResponse(response, false);
        next();
    }

    @Override
    protected void processWrite() throws Exception {
        super.processWrite();
        next();
    }

    private void next() throws IOException {
        if (isNull(dataIterator)) throw new IllegalArgumentException("Iterator is null");
        while (dataIterator.hasNext() && isNull(queueHead)) {
            final var record = dataIterator.next();
            final var key = BufferConverter.unfoldToBytes(record.getKey());
            final var value = BufferConverter.unfoldToBytes(record.getValue());
            final var dataSize = key.length + SPLIT.length + value.length;
            final var hexLength = Integer.toHexString(dataSize).getBytes(Charset.defaultCharset());
            final var chunk = new byte[hexLength.length + dataSize + 2 * CRLF.length];
            final var chunkBuffer = ByteBuffer.wrap(chunk);
            chunkBuffer.put(hexLength).put(CRLF).put(key).put(SPLIT).put(value).put(CRLF);
            write(chunk,0, chunk.length);
        }
        if (dataIterator.hasNext()) return;

        write(END_STREAM, 0, END_STREAM.length);
        Request handling = this.handling;
        if (isNull(handling)) {
            throw new IOException("Out of order response");
        }
        server.incRequestsProcessed();
        String connection = handling.getHeader("Connection: ");
        boolean keepAlive = handling.isHttp11()
                ? !"close".equalsIgnoreCase(connection)
                : "Keep-Alive".equalsIgnoreCase(connection);
        if(!keepAlive) scheduleClose();
        if ((this.handling = handling = pipeline.pollFirst()) != null) {
            if (handling == FIN) {
                scheduleClose();
            } else {
                server.handleRequest(handling, this);
            }
        }
    }
}