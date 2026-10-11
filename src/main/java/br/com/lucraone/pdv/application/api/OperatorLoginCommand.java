package br.com.lucraone.pdv.application.api;

/** Username and password are never logged; toString() is redacted. */
public record OperatorLoginCommand(String username, String password) {

    @Override
    public String toString() {
        return "OperatorLoginCommand[username=" + username + ",password=REDACTED]";
    }
}
