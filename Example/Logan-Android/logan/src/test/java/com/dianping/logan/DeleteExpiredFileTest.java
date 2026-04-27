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
import static org.junit.Assert.assertTrue;

public class DeleteExpiredFileTest {

    private static final long TODAY     = 1502068800000L;     // any "today"
    private static final long YESTERDAY = TODAY - 86_400_000L;
    private static final long WEEK_AGO  = TODAY - 7L * 86_400_000L;

    private static boolean shouldDelete(String name, long deleteTime) {
        return LoganThread.shouldDeleteForExpiry(name, deleteTime);
    }

    @Test
    public void deletesLegacyExpired() {
        assertTrue(shouldDelete(String.valueOf(WEEK_AGO), YESTERDAY));
    }

    @Test
    public void deletesTypedExpired() {
        assertTrue(shouldDelete(WEEK_AGO + "_1", YESTERDAY));
        assertTrue(shouldDelete(WEEK_AGO + "_42", YESTERDAY));
    }

    @Test
    public void keepsLegacyFresh() {
        assertFalse(shouldDelete(String.valueOf(TODAY), YESTERDAY));
    }

    @Test
    public void keepsTypedFresh() {
        assertFalse(shouldDelete(TODAY + "_1", YESTERDAY));
    }

    @Test
    public void skipsCopySnapshots() {
        assertFalse(shouldDelete(WEEK_AGO + ".copy", YESTERDAY));
        assertFalse(shouldDelete(WEEK_AGO + "_1.copy", YESTERDAY));
    }

    @Test
    public void skipsGarbage() {
        assertFalse(shouldDelete("garbage", YESTERDAY));
        assertFalse(shouldDelete("", YESTERDAY));
        assertFalse(shouldDelete("_1", YESTERDAY));
    }

    @Test
    public void boundaryEqualsDeleteTime() {
        // spec preserves <=  rule from the original parser
        assertTrue(shouldDelete(String.valueOf(YESTERDAY), YESTERDAY));
        assertTrue(shouldDelete(YESTERDAY + "_1", YESTERDAY));
    }

    @Test
    public void shouldDelete_nonNullSafety() {
        assertEquals(false, shouldDelete(null, YESTERDAY));
    }
}
