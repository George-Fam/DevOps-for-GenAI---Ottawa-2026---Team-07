package com.yami.governance;

/**
 * Exception levée quand l'intégrité du manifest de gouvernance est compromise.
 * Unchecked — le Harness doit arrêter immédiatement (hard fail).
 */
public class GovManifestException extends RuntimeException {

    public GovManifestException(String message) {
        super(message);
    }

    public GovManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
