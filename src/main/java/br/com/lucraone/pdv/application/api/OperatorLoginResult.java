package br.com.lucraone.pdv.application.api;

public record OperatorLoginResult(OperatorSessionData sessionData, OperatorCredential credential, String requestId) {
}
