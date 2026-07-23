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

import java.util.Optional;

import org.osgi.annotation.versioning.ConsumerType;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Pluggable bearer-token verification for the MCP HTTP endpoint. When a
 * verifier service is wired to an MCP server component (standard DS target
 * selection via the {@code verifier.target} configuration property), the
 * authentication servlet filter delegates every request to it instead of
 * comparing against the static {@code auth.token}; enforcement stays in the
 * filter, in front of the MCP servlet.
 * <p>
 * Implementations validate the token however the deployment requires — e.g.
 * JWT signature/issuer/audience/expiry against a JWKS, or OAuth2 token
 * introspection — and return the per-client identity on success. Any
 * exception thrown by an implementation is treated as a rejection
 * (fail-closed).
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@ConsumerType
public interface McpTokenVerifier {

	/**
	 * Request attribute under which the filter exposes the verified
	 * {@link McpPrincipal} to downstream servlets.
	 */
	String PRINCIPAL_ATTRIBUTE = "org.eclipse.fennec.mcp.auth.principal";

	/**
	 * Verifies a presented bearer token.
	 *
	 * @param bearerToken the token presented in the {@code Authorization: Bearer}
	 *            header, never {@code null} or blank
	 * @param request     the request, for verifiers that consult additional
	 *            request context (never used to bypass token validation)
	 * @return the verified principal, or {@link Optional#empty()} to reject
	 */
	Optional<McpPrincipal> verify(String bearerToken, HttpServletRequest request);
}
