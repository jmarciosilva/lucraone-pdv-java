/**
 * Home of the platform-specific adapters that protect secrets at rest, behind the application port
 * {@code br.com.lucraone.pdv.application.security.SecretProtector}.
 *
 * <p>Implemented today: {@code WindowsDpapiSecretProtector}, which uses Windows DPAPI in the scope of the
 * current user.
 *
 * <p>Approved direction, not yet implemented: one secret store per platform, so that the PDV is secure on
 * every target rather than only on Windows.
 *
 * <ul>
 *   <li>Windows: DPAPI, already available</li>
 *   <li>Linux: Secret Service / libsecret, or an equivalent trusted keyring</li>
 *   <li>macOS: Keychain</li>
 * </ul>
 *
 * <p>There is deliberately no fallback. If no native store is available, the application must fail
 * secure: a credential is never written as plain text, into a properties file, into the local database
 * without protection, or into the source. The machine credential issued by pairing therefore lives only
 * in memory until this architecture exists, which is expected in the authentication phase.
 */
package br.com.lucraone.pdv.infrastructure.security;
