/*
 * Copyright (c) 2018-present, 美团点评
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.dianping.logan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileNamesTest {

    private static final long DAY = 1502068800000L;

    @Test
    public void parse_legacyBare() {
        FileNames.Parsed p = FileNames.parse("1502068800000");
        assertNotNull(p);
        assertEquals(DAY, p.dateMillis);
        assertNull(p.type);
        assertFalse(p.isCopy);
    }

    @Test
    public void parse_typed() {
        FileNames.Parsed p = FileNames.parse("1502068800000_1");
        assertNotNull(p);
        assertEquals(DAY, p.dateMillis);
        assertEquals(Integer.valueOf(1), p.type);
        assertFalse(p.isCopy);
    }

    @Test
    public void parse_legacyCopy() {
        FileNames.Parsed p = FileNames.parse("1502068800000.copy");
        assertNotNull(p);
        assertEquals(DAY, p.dateMillis);
        assertNull(p.type);
        assertTrue(p.isCopy);
    }

    @Test
    public void parse_typedCopy() {
        FileNames.Parsed p = FileNames.parse("1502068800000_1.copy");
        assertNotNull(p);
        assertEquals(DAY, p.dateMillis);
        assertEquals(Integer.valueOf(1), p.type);
        assertTrue(p.isCopy);
    }

    @Test
    public void parse_garbageReturnsNull() {
        assertNull(FileNames.parse("garbage"));
        assertNull(FileNames.parse(""));
        assertNull(FileNames.parse(null));
        assertNull(FileNames.parse("_1"));
        assertNull(FileNames.parse("1502068800000_abc"));
        assertNull(FileNames.parse("abc_1"));
    }

    @Test
    public void parse_extraSegmentsRejected() {
        assertNull(FileNames.parse("1502068800000_1_extra"));
    }

    @Test
    public void compose_legacy() {
        assertEquals("1502068800000", FileNames.compose(DAY, null));
    }

    @Test
    public void compose_typed() {
        assertEquals("1502068800000_1", FileNames.compose(DAY, 1));
        assertEquals("1502068800000_0", FileNames.compose(DAY, 0));
    }
}
