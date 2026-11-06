package com.dfc.mosquittoui.importer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Retained messages from a mosquitto.db (src/persist.h of Mosquitto, the same for versions 5 and 6 of the format, which
 * cover Mosquitto 1.5 up to 2.1). Lengths are in network byte order, the ids and the expiry time in that of the machine
 * that wrote the file. The message of a retain chunk is a message chunk with the same id.
 */
public final class DbFile {

    private static final byte[] MAGIC = {0x00, (byte) 0xB5, 0x00, 'm', 'o', 's', 'q', 'u', 'i', 't', 't', 'o', ' ', 'd', 'b'};
    private static final int CHUNK_CFG = 1;
    private static final int CHUNK_MESSAGE = 2;
    private static final int CHUNK_RETAIN = 4;
    /** After the last id (8 bytes) and the shutdown flag. */
    private static final int CFG_ID_SIZE_OFFSET = 9;

    public record Message(String topic, byte[] payload, int qos, long expiryTime, boolean hasProperties) {
    }

    public final int version;
    public final List<Message> retained = new ArrayList<>();

    private DbFile(int version) {
        this.version = version;
    }

    public static boolean isDbFile(byte[] data) {
        return data.length >= MAGIC.length && Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC);
    }

    public static DbFile parse(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data);
        if (data.length < MAGIC.length + 8 || !isDbFile(data)) {
            throw new InvalidFileException("Not a mosquitto.db file");
        }
        in.position(MAGIC.length + 4);
        int version = in.getInt();
        if (version != 5 && version != 6) {
            throw new InvalidFileException("Unsupported version " + version + " of the file format");
        }
        DbFile file = new DbFile(version);
        int idSize = Long.BYTES;
        Map<String, Message> messages = new HashMap<>();
        List<String> retains = new ArrayList<>();
        try {
            while (in.remaining() >= 8) {
                int chunk = in.getInt();
                int length = in.getInt();
                int end = Math.addExact(in.position(), length);
                if (length < 0 || end > data.length) {
                    throw new InvalidFileException("The file is truncated");
                }
                if (chunk == CHUNK_CFG) {
                    idSize = data[in.position() + CFG_ID_SIZE_OFFSET];
                } else if (chunk == CHUNK_MESSAGE) {
                    String id = id(in, idSize);
                    messages.put(id, message(in, end));
                } else if (chunk == CHUNK_RETAIN) {
                    retains.add(id(in, idSize));
                }
                in.position(end);
            }
        } catch (ArithmeticException | IllegalArgumentException | IndexOutOfBoundsException | java.nio.BufferUnderflowException e) {
            throw new InvalidFileException("The file is damaged");
        }
        for (String id : retains) {
            Message message = messages.get(id);
            if (message != null) {
                file.retained.add(message);
            }
        }
        return file;
    }

    private static String id(ByteBuffer in, int size) {
        byte[] id = new byte[size];
        in.get(id);
        return Arrays.toString(id);
    }

    private static Message message(ByteBuffer in, int end) {
        long expiry = in.duplicate().order(ByteOrder.nativeOrder()).getLong();
        in.position(in.position() + Long.BYTES);
        int payloadLength = in.getInt();
        in.getShort(); // source mid
        int sourceId = Short.toUnsignedInt(in.getShort());
        int sourceUsername = Short.toUnsignedInt(in.getShort());
        int topicLength = Short.toUnsignedInt(in.getShort());
        in.getShort(); // source port
        int qos = in.get();
        in.get(); // retain
        in.position(in.position() + sourceId + sourceUsername);
        byte[] topic = new byte[topicLength];
        in.get(topic);
        byte[] payload = new byte[payloadLength];
        in.get(payload);
        return new Message(new String(topic, StandardCharsets.UTF_8), payload, qos, expiry, in.position() < end);
    }
}
