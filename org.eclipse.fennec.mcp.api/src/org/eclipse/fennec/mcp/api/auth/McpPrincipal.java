/*
 * ******************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Data In Motion Consulting - initial implementation
 * ******************************************************************
 */
package org.eclipse.fennec.mcp.api.auth;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The verified identity of an MCP client, produced by a
 * {@link McpTokenVerifier} and exposed to downstream code as the
 * {@link McpTokenVerifier#PRINCIPAL_ATTRIBUTE} request attribute.
 *
 * @param clientId  the per-client identity (e.g. the JWT {@code sub} claim), never {@code null}
 * @param expiresAt the token expiry, or {@code null} for non-expiring credentials
 * @param scopes    granted scopes, never {@code null} (may be empty)
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public record McpPrincipal(String clientId, Instant expiresAt, List<String> scopes) {

	public McpPrincipal {
		Objects.requireNonNull(clientId, "clientId must not be null");
		scopes = scopes == null ? List.of() : List.copyOf(scopes);
	}

	/**
	 * @param clientId the per-client identity
	 * @return a principal without expiry and scopes
	 */
	public static McpPrincipal of(String clientId) {
		return new McpPrincipal(clientId, null, List.of());
	}
}
