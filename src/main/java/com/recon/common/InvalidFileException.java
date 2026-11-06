package com.recon.common;

/** The file as a whole is unusable. It is unchecked so the surrounding transaction rolls back. */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
