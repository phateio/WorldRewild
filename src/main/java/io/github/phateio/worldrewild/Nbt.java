package io.github.phateio.worldrewild;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal read-only NBT parser for already-decompressed chunk payloads — just
 * enough to reach a chunk's {@code structures.starts}. Compounds map to
 * {@code Map<String,Object>}, lists to {@code List<Object>}, arrays to
 * {@code byte[]/int[]/long[]}, numbers to boxed primitives, strings to
 * {@code String}. Deliberately independent of the server's NBT classes so the
 * structure scan works purely from region files.
 */
final class Nbt {

    private Nbt() {
    }

    /** Parse an uncompressed NBT payload and return the root compound (empty if none). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = in.readUnsignedByte();
            if (type != 10) {
                return Map.of();
            }
            in.skipBytes(in.readUnsignedShort()); // root name
            return (Map<String, Object>) readPayload(in, 10);
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] a = new byte[in.readInt()];
                in.readFully(a);
                return a;
            }
            case 8: {
                byte[] a = new byte[in.readUnsignedShort()];
                in.readFully(a);
                return new String(a, StandardCharsets.UTF_8);
            }
            case 9: {
                int elem = in.readUnsignedByte();
                int n = Math.max(0, in.readInt());
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(elem == 0 ? null : readPayload(in, elem));
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = new HashMap<>();
                int t;
                while ((t = in.readUnsignedByte()) != 0) {
                    byte[] nm = new byte[in.readUnsignedShort()];
                    in.readFully(nm);
                    map.put(new String(nm, StandardCharsets.UTF_8), readPayload(in, t));
                }
                return map;
            }
            case 11: {
                int[] a = new int[in.readInt()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case 12: {
                long[] a = new long[in.readInt()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("unknown NBT tag " + type);
        }
    }
}
