/**
 * Home of the platform-specific adapters for operating-system detail: operational directories, path
 * resolution and system information.
 *
 * <p>Approved direction, not yet implemented. The PDV is a multiplatform Java application, so the inner
 * layers never inspect the operating system; every such detail is expected to land here behind a port
 * such as {@code PlatformPaths}, with one adapter per platform:
 *
 * <ul>
 *   <li>Windows: {@code %LOCALAPPDATA%\LucraOne\PDV}</li>
 *   <li>Linux: XDG Base Directory, {@code $XDG_DATA_HOME/LucraOne/PDV}, falling back to
 *       {@code ~/.local/share/LucraOne/PDV}</li>
 *   <li>macOS: {@code ~/Library/Application Support/LucraOne/PDV}</li>
 * </ul>
 *
 * <p>Today {@code infrastructure.persistence.LocalDataDirectory} still resolves {@code LOCALAPPDATA}
 * directly, which is why the operational database cannot be opened outside Windows. Moving it here is a
 * change of its own, with its own tests per platform, and was deliberately kept out of the API phase.
 */
package br.com.lucraone.pdv.infrastructure.platform;
