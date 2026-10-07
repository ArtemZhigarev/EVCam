package com.kooo.evcam;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AfmcVhalPropsTest {
    private static final int USAGE = 0x21408030;

    // --- a tiny protobuf writer for building stream messages the way the HAL sends them ---
    private static void varint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static void tag(ByteArrayOutputStream out, int field, int wire) {
        varint(out, ((long) field << 3) | wire);
    }

    private static void bytes(ByteArrayOutputStream out, int field, byte[] b) {
        tag(out, field, 2);
        varint(out, b.length);
        out.write(b, 0, b.length);
    }

    private static long zz(int n) {
        return ((n << 1) ^ (n >> 31)) & 0xFFFFFFFFL;
    }

    /** VehiclePropValue with its int32 values packed and zigzag-encoded (sint32). */
    private static byte[] value(int prop, int area, int status, int... ints) {
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        tag(v, 1, 0); varint(v, prop & 0xFFFFFFFFL);
        tag(v, 2, 0); varint(v, 0x00400000);
        tag(v, 3, 0); varint(v, 1796122909703L);
        if (area != 0) { tag(v, 4, 0); varint(v, area); }
        if (ints.length > 0) {
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            for (int i : ints) varint(p, zz(i));
            bytes(v, 5, p.toByteArray());
        }
        bytes(v, 7, new byte[]{0, 0, (byte) 0x80, 0x3f});  // packed float 1.0
        bytes(v, 8, "x".getBytes());
        if (status != 0) { tag(v, 10, 0); varint(v, status); }
        return v.toByteArray();
    }

    private static byte[] update(byte[] value) {
        ByteArrayOutputStream u = new ByteArrayOutputStream();
        bytes(u, 1, value);
        tag(u, 2, 0); varint(u, 1);
        return u.toByteArray();
    }

    private static byte[] batch(byte[]... values) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] v : values) bytes(b, 1, update(v));
        return b.toByteArray();
    }

    @Test
    public void readsUsageModeFromABatch() {
        byte[] b = batch(value(0x11600207, 0, 0, 0), value(USAGE, 0, 0, 2), value(0x21408033, 0, 0, 3));
        assertEquals(Integer.valueOf(2), AfmcVhalProps.findInt32(b, USAGE));
        assertEquals(Integer.valueOf(3), AfmcVhalProps.findInt32(b, 0x21408033));
    }

    @Test
    public void valuesAreZigzagSoOneAndTwoAreNotMixedUp() {
        assertEquals(Integer.valueOf(1), AfmcVhalProps.findInt32(batch(value(USAGE, 0, 0, 1)), USAGE));
        assertEquals(Integer.valueOf(0), AfmcVhalProps.findInt32(batch(value(USAGE, 0, 0, 0)), USAGE));
        assertEquals(Integer.valueOf(-5), AfmcVhalProps.findInt32(batch(value(USAGE, 0, 0, -5)), USAGE));
        assertEquals(Integer.valueOf(1), AfmcVhalProps.findInt32(batch(value(USAGE, 0, 0, 1, 7)), USAGE));
    }

    @Test
    public void absentUnavailableOrOtherAreaIsNull() {
        assertNull(AfmcVhalProps.findInt32(batch(value(0x21408033, 0, 0, 3)), USAGE));
        assertNull(AfmcVhalProps.findInt32(batch(value(USAGE, 0, 1, 2)), USAGE));
        assertNull(AfmcVhalProps.findInt32(batch(value(USAGE, 1, 0, 2)), USAGE));
        assertNull(AfmcVhalProps.findInt32(batch(value(USAGE, 0, 0)), USAGE));
        assertNull(AfmcVhalProps.findInt32(new byte[0], USAGE));
        assertNull(AfmcVhalProps.findInt32(null, USAGE));
    }

    @Test
    public void newestOfTwoInOneBatchWins() {
        assertEquals(Integer.valueOf(1),
                AfmcVhalProps.findInt32(batch(value(USAGE, 0, 0, 2), value(USAGE, 0, 0, 1)), USAGE));
    }

    @Test
    public void truncatedOrBrokenBatchDoesNotThrow() {
        byte[] full = batch(value(USAGE, 0, 0, 1), value(0x21408033, 0, 0, 3));
        byte[] cut = Arrays.copyOf(full, full.length - 3);
        assertEquals(Integer.valueOf(1), AfmcVhalProps.findInt32(cut, USAGE));
        assertNull(AfmcVhalProps.findInt32(new byte[]{(byte) 0x0a, (byte) 0xff}, USAGE));
        assertNull(AfmcVhalProps.findInt32(new byte[]{(byte) 0x0f, 1, 2}, USAGE));
    }

    @Test
    public void zigzagDecoding() {
        assertEquals(0, AfmcVhalProps.zigzag(0));
        assertEquals(-1, AfmcVhalProps.zigzag(1));
        assertEquals(1, AfmcVhalProps.zigzag(2));
        assertEquals(2, AfmcVhalProps.zigzag(4));
    }
}
