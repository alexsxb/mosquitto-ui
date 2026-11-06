package com.dfc.mosquittoui.importer;

/** An uploaded file cannot be read. */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
