package com.kooo.evcam;

/**
 * AppsForMyCar fork: reads one int property out of a message from the vehicle HAL's property
 * stream (gRPC vhal_proto.VehicleServer/StartPropertyValuesStream on 127.0.0.1:40004, the same
 * channel upstream's VhalSignalObserver and the car's Custom Profiles app use). Pure Java, for tests.
 *
 * Wire layout, read from Custom Profiles' generated classes (protobuf-lite message info):
 *   batch   { repeated update values = 1; }
 *   update  { VehiclePropValue value = 1; bool update_status = 2; }
 *   VehiclePropValue { int32 prop = 1; int32 value_type = 2; int64 timestamp = 3; int32 area_id = 4;
 *                      repeated sint32 int32_values = 5 (packed, zigzag); ...; int32 status = 10; }
 * status 0 = available.
 */
final class AfmcVhalProps {
    private AfmcVhalProps() {}

    /**
     * The first int32 value of [prop] (area 0) in [batch], or null when the batch doesn't carry it,
     * carries it as unavailable, or can't be read. If the batch carries it more than once, the last
     * one wins (the newest).
     */
    static Integer findInt32(byte[] batch, int prop) {
        if (batch == null) return null;
        Integer found = null;
        try {
            Reader r = new Reader(batch, 0, batch.length);
            while (r.more()) {
                int key = (int) r.varint();
                if ((key >>> 3) == 1 && (key & 7) == 2) {
                    int len = (int) r.varint();
                    Integer v = fromUpdate(batch, r.pos, r.pos + len, prop);
                    if (v != null) found = v;
                    r.pos += len;
                } else {
                    r.skip(key & 7);
                }
            }
        } catch (RuntimeException e) {
            return found;
        }
        return found;
    }

    private static Integer fromUpdate(byte[] b, int start, int end, int prop) {
        Reader r = new Reader(b, start, end);
        while (r.more()) {
            int key = (int) r.varint();
            if ((key >>> 3) == 1 && (key & 7) == 2) {
                int len = (int) r.varint();
                return fromValue(b, r.pos, r.pos + len, prop);
            }
            r.skip(key & 7);
        }
        return null;
    }

    private static Integer fromValue(byte[] b, int start, int end, int prop) {
        Reader r = new Reader(b, start, end);
        Integer propId = null;
        int area = 0;
        int status = 0;
        Integer first = null;
        while (r.more()) {
            int key = (int) r.varint();
            int field = key >>> 3;
            int wire = key & 7;
            if (field == 1 && wire == 0) {
                propId = (int) r.varint();
            } else if (field == 4 && wire == 0) {
                area = (int) r.varint();
            } else if (field == 10 && wire == 0) {
                status = (int) r.varint();
            } else if (field == 5 && wire == 2) {
                int len = (int) r.varint();
                Reader packed = new Reader(b, r.pos, r.pos + len);
                if (first == null && packed.more()) first = zigzag(packed.varint());
                r.pos += len;
            } else if (field == 5 && wire == 0) {
                long v = r.varint();
                if (first == null) first = zigzag(v);
            } else {
                r.skip(wire);
            }
        }
        if (propId == null || propId != prop || area != 0 || status != 0) return null;
        return first;
    }

    static int zigzag(long v) {
        int n = (int) v;
        return (n >>> 1) ^ -(n & 1);
    }

    private static final class Reader {
        final byte[] b;
        int pos;
        final int end;

        Reader(byte[] b, int pos, int end) {
            if (end > b.length || pos < 0 || pos > end) throw new IllegalArgumentException("bad range");
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        long varint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IllegalArgumentException("truncated");
                int c = b[pos++] & 0xff;
                result |= (long) (c & 0x7f) << shift;
                if (c < 0x80) return result;
            }
            throw new IllegalArgumentException("bad varint");
        }

        void skip(int wire) {
            switch (wire) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: { int len = (int) varint(); pos += len; break; }
                case 5: pos += 4; break;
                default: throw new IllegalArgumentException("wire type " + wire);
            }
            if (pos > end) throw new IllegalArgumentException("truncated");
        }
    }
}
