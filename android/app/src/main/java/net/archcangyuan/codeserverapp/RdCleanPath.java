package net.archcangyuan.codeserverapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * DER codec for the RDCleanPath PDU (IronRDP {@code ironrdp-rdcleanpath}),
 * the handshake between the IronRDP web client and its WebSocket gateway.
 *
 * <pre>
 * RDCleanPathPdu ::= SEQUENCE {
 *   version            [0] INTEGER,              -- 3390
 *   error              [1] RDCleanPathErr OPTIONAL,
 *   destination        [2] UTF8String OPTIONAL,
 *   proxyAuth          [3] UTF8String OPTIONAL,
 *   serverAuth         [4] UTF8String OPTIONAL,
 *   preconnectionBlob  [5] UTF8String OPTIONAL,
 *   x224ConnectionPdu  [6] OCTET STRING OPTIONAL,
 *   serverCertChain    [7] SEQUENCE OF OCTET STRING OPTIONAL,
 *   serverAddr         [9] UTF8String OPTIONAL }
 * RDCleanPathErr ::= SEQUENCE {
 *   errorCode [0] INTEGER, httpStatusCode [1] INTEGER OPTIONAL,
 *   wsaLastError [2] INTEGER OPTIONAL, tlsAlertCode [3] INTEGER OPTIONAL }
 * </pre>
 * All context tags are EXPLICIT.
 */
final class RdCleanPath {
    static final long VERSION_1 = 3390;
    static final int GENERAL_ERROR = 1;
    static final int NEGOTIATION_ERROR = 2;

    private static final int TAG_INTEGER = 0x02;
    private static final int TAG_OCTET_STRING = 0x04;
    private static final int TAG_UTF8_STRING = 0x0C;
    private static final int TAG_SEQUENCE = 0x30;

    /** The fields a client request carries. */
    static final class Request {
        final String destination;
        final String proxyAuth;
        final byte[] x224ConnectionRequest;

        Request(String destination, String proxyAuth, byte[] x224ConnectionRequest) {
            this.destination = destination;
            this.proxyAuth = proxyAuth;
            this.x224ConnectionRequest = x224ConnectionRequest;
        }
    }

    private RdCleanPath() {}

    /**
     * Returns the total length of the DER PDU at the start of {@code data}, or
     * -1 when more bytes are needed.
     */
    static int pduLength(byte[] data, int length) throws IOException {
        if (length < 2) {
            return -1;
        }
        if ((data[0] & 0xFF) != TAG_SEQUENCE) {
            throw new IOException("Not an RDCleanPath PDU");
        }
        int first = data[1] & 0xFF;
        if (first < 0x80) {
            return 2 + first;
        }
        int count = first & 0x7F;
        if (count == 0 || count > 4) {
            throw new IOException("Unsupported RDCleanPath length");
        }
        if (length < 2 + count) {
            return -1;
        }
        int value = 0;
        for (int index = 0; index < count; index++) {
            value = (value << 8) | (data[2 + index] & 0xFF);
        }
        return 2 + count + value;
    }

    static Request decodeRequest(byte[] pdu) throws IOException {
        Reader outer = new Reader(pdu, 0, pdu.length);
        Reader sequence = outer.expect(TAG_SEQUENCE);
        long version = -1;
        String destination = null;
        String proxyAuth = null;
        byte[] x224 = null;
        while (sequence.hasMore()) {
            int tag = sequence.peekTag();
            Reader field = sequence.next();
            if ((tag & 0xE0) != 0xA0) {
                continue;
            }
            switch (tag & 0x1F) {
            case 0:
                version = field.readInteger();
                break;
            case 2:
                destination = field.readString();
                break;
            case 3:
                proxyAuth = field.readString();
                break;
            case 6:
                x224 = field.expect(TAG_OCTET_STRING).remaining();
                break;
            default:
                break;
            }
        }
        if (version != VERSION_1) {
            throw new IOException("Unsupported RDCleanPath version " + version);
        }
        if (destination == null || proxyAuth == null || x224 == null) {
            throw new IOException("Incomplete RDCleanPath request");
        }
        return new Request(destination, proxyAuth, x224);
    }

    static byte[] encodeResponse(String serverAddr, byte[] x224Response, List<byte[]> certChain) {
        ByteArrayOutputStream chain = new ByteArrayOutputStream();
        for (byte[] certificate : certChain) {
            writeTlv(chain, TAG_OCTET_STRING, certificate);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeExplicit(body, 0, integer(VERSION_1));
        writeExplicit(body, 6, tlv(TAG_OCTET_STRING, x224Response));
        writeExplicit(body, 7, tlv(TAG_SEQUENCE, chain.toByteArray()));
        writeExplicit(body, 9, tlv(TAG_UTF8_STRING, serverAddr.getBytes(StandardCharsets.UTF_8)));
        return tlv(TAG_SEQUENCE, body.toByteArray());
    }

    /** A general error, optionally with an HTTP status (e.g. 401 when sign-in is needed). */
    static byte[] encodeGeneralError(Integer httpStatus) {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        writeExplicit(error, 0, integer(GENERAL_ERROR));
        if (httpStatus != null) {
            writeExplicit(error, 1, integer(httpStatus));
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeExplicit(body, 0, integer(VERSION_1));
        writeExplicit(body, 1, tlv(TAG_SEQUENCE, error.toByteArray()));
        return tlv(TAG_SEQUENCE, body.toByteArray());
    }

    /** Negotiation failure carrying the server's X.224 response (e.g. "CredSSP required"). */
    static byte[] encodeNegotiationError(byte[] x224Response) {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        writeExplicit(error, 0, integer(NEGOTIATION_ERROR));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeExplicit(body, 0, integer(VERSION_1));
        writeExplicit(body, 1, tlv(TAG_SEQUENCE, error.toByteArray()));
        writeExplicit(body, 6, tlv(TAG_OCTET_STRING, x224Response));
        return tlv(TAG_SEQUENCE, body.toByteArray());
    }

    private static byte[] integer(long value) {
        // Minimal two's-complement big-endian encoding of a non-negative value.
        int length = 1;
        while (length < 8 && (value >> (8 * length - 1)) != 0) {
            length++;
        }
        byte[] content = new byte[length];
        for (int index = 0; index < length; index++) {
            content[length - 1 - index] = (byte) (value >> (8 * index));
        }
        return tlv(TAG_INTEGER, content);
    }

    private static void writeExplicit(ByteArrayOutputStream out, int tagNumber, byte[] inner) {
        writeTlv(out, 0xA0 | tagNumber, inner);
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTlv(out, tag, content);
        return out.toByteArray();
    }

    private static void writeTlv(ByteArrayOutputStream out, int tag, byte[] content) {
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length);
        } else if (length < 0x1000000) {
            out.write(0x83);
            out.write(length >> 16);
            out.write(length >> 8);
            out.write(length);
        } else {
            out.write(0x84);
            out.write(length >>> 24);
            out.write(length >> 16);
            out.write(length >> 8);
            out.write(length);
        }
        out.write(content, 0, content.length);
    }

    /** Sequential DER TLV reader over a slice. */
    private static final class Reader {
        private final byte[] data;
        private int position;
        private final int end;

        Reader(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        boolean hasMore() {
            return position < end;
        }

        int peekTag() throws IOException {
            if (position >= end) {
                throw new IOException("Truncated RDCleanPath PDU");
            }
            return data[position] & 0xFF;
        }

        /** Reads the next TLV and returns a reader over its content. */
        Reader next() throws IOException {
            peekTag();
            position++;
            if (position >= end) {
                throw new IOException("Truncated RDCleanPath PDU");
            }
            int first = data[position++] & 0xFF;
            int length;
            if (first < 0x80) {
                length = first;
            } else {
                int count = first & 0x7F;
                if (count == 0 || count > 4 || position + count > end) {
                    throw new IOException("Invalid RDCleanPath length");
                }
                length = 0;
                for (int index = 0; index < count; index++) {
                    length = (length << 8) | (data[position++] & 0xFF);
                }
            }
            if (length < 0 || position + length > end) {
                throw new IOException("Truncated RDCleanPath PDU");
            }
            Reader content = new Reader(data, position, position + length);
            position += length;
            return content;
        }

        Reader expect(int tag) throws IOException {
            if (peekTag() != tag) {
                throw new IOException("Unexpected RDCleanPath tag " + peekTag());
            }
            return next();
        }

        long readInteger() throws IOException {
            byte[] content = expect(TAG_INTEGER).remaining();
            long value = 0;
            for (byte octet : content) {
                value = (value << 8) | (octet & 0xFF);
            }
            return value;
        }

        String readString() throws IOException {
            return new String(expect(TAG_UTF8_STRING).remaining(), StandardCharsets.UTF_8);
        }

        byte[] remaining() {
            byte[] copy = new byte[end - position];
            System.arraycopy(data, position, copy, 0, copy.length);
            position = end;
            return copy;
        }
    }
}
