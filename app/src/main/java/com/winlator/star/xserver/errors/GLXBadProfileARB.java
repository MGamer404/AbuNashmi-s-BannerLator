/*
 * Ported from Pipetto-crypto/winlator (branch winlator_bionic, commit 60c291b9),
 * app/src/main/java/com/winlator/cmod/xserver/errors/GLXBadProfileARB.java.
 * Original work Copyright (c) 2023 BrunoSX and contributors; MIT License.
 * Adapted for com.winlator.star: package rename.
 * See THIRD-PARTY-LICENSES.md.
 */
package com.winlator.star.xserver.errors;

public class GLXBadProfileARB extends XRequestError {
    public GLXBadProfileARB(int id) {
        super(GLXError.BASE_ERROR_CODE + 13, id);
    }
}
