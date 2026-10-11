package br.com.lucraone.pdv.application.operator;

import br.com.lucraone.pdv.application.api.OperatorSessionData;
import java.util.Optional;

/**
 * Immutable view of the operator session state. No credential or password is carried here.
 * Sensitive data stays only in {@link OperatorSessionService}.
 */
public record OperatorAuthSnapshot(
        OperatorAuthState state,
        Optional<OperatorSessionData> session,
        Optional<String> requestId
) {

    public static OperatorAuthSnapshot of(OperatorAuthState state) {
        return new OperatorAuthSnapshot(state, Optional.empty(), Optional.empty());
    }
}
