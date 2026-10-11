package br.com.lucraone.pdv.application.operator;

public enum OperatorAuthState {

    /** No operator is authenticated; login required. */
    NOT_AUTHENTICATED,

    /** An operator session is active and not expired. */
    AUTHENTICATED,

    /** The session token was rejected by the backend. */
    SESSION_INVALID,

    /** A concurrent session conflict was detected (409). */
    SESSION_CONFLICT,

    /** Wrong username or password. */
    LOGIN_FAILED,

    /** The backend is not reachable and no cached session is available. */
    UNAVAILABLE
}
