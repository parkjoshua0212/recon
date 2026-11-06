package com.recon.feed.service;

/** Helpers for MySQL text columns, whose lengths count characters rather than Java chars. */
final class Text {

    private Text() {}

    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    /** Cuts by code point, because splitting an emoji in half produces text MySQL refuses to store. */
    static String truncate(String value, int maxLength) {
        if (length(value) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }
}
