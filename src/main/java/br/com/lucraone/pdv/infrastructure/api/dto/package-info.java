/**
 * Wire-shaped representations of the PDV API payloads. These records mirror the backend JSON exactly and
 * are the only place in the project allowed to carry Jackson annotations. Timestamps stay as strings here
 * so that an unparseable value is reported as an invalid response rather than as a deserialization crash.
 */
package br.com.lucraone.pdv.infrastructure.api.dto;
