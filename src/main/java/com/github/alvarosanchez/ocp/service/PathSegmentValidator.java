package com.github.alvarosanchez.ocp.service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class PathSegmentValidator {

    private PathSegmentValidator() {
    }

    static String requireSinglePathSegment(String value, String fieldLabel) {
        String normalizedValue = value == null ? "" : value.trim();
        if (normalizedValue.isBlank()) {
            throw new IllegalStateException(fieldLabel + " is required.");
        }
        validateSinglePathSegment(normalizedValue, fieldLabel);
        return normalizedValue;
    }

    static void validateSinglePathSegment(String value, String fieldLabel) {
        try {
            Path normalizedPath = Path.of(value).normalize();
            if (normalizedPath.isAbsolute() || normalizedPath.getNameCount() != 1 || value.contains("/") || value.contains("\\")) {
                throw new IllegalStateException(fieldLabel + " must be a single safe path segment.");
            }
            String segment = normalizedPath.getFileName().toString();
            if (!value.equals(segment) || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalStateException(fieldLabel + " must be a single safe path segment.");
            }
        } catch (InvalidPathException e) {
            throw new IllegalStateException(fieldLabel + " must be a single safe path segment.", e);
        }
    }
}
