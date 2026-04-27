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

final class FileNames {

    static final String COPY_SUFFIX = ".copy";

    private FileNames() {}

    static final class Parsed {
        final long dateMillis;
        final Integer type;     // null => legacy bare {date}
        final boolean isCopy;

        Parsed(long dateMillis, Integer type, boolean isCopy) {
            this.dateMillis = dateMillis;
            this.type = type;
            this.isCopy = isCopy;
        }
    }

    /**
     * Parses a Logan log filename. Returns null if the name does not match
     * either {date} / {date}_{type} / {date}.copy / {date}_{type}.copy.
     */
    static Parsed parse(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String body = name;
        boolean isCopy = false;
        if (name.endsWith(COPY_SUFFIX)) {
            isCopy = true;
            body = name.substring(0, name.length() - COPY_SUFFIX.length());
        }
        String[] parts = body.split("_", -1);
        if (parts.length < 1 || parts.length > 2) {
            return null;
        }
        long dateMillis;
        try {
            dateMillis = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        Integer type = null;
        if (parts.length == 2) {
            try {
                type = Integer.valueOf(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Parsed(dateMillis, type, isCopy);
    }

    /**
     * Composes a Logan log filename for a given date and (optional) type.
     * type == null -> legacy bare form {dateMillis}.
     */
    static String compose(long dateMillis, Integer type) {
        if (type == null) {
            return String.valueOf(dateMillis);
        }
        return dateMillis + "_" + type;
    }
}
