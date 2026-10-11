package br.com.lucraone.pdv.application.api;

/** The delivery secret is never logged. */
public record CredentialRotationCommand(String operationId, String deliverySecret) {

    @Override
    public String toString() {
        return "CredentialRotationCommand[operationId=" + operationId + ",deliverySecret=REDACTED]";
    }
}
