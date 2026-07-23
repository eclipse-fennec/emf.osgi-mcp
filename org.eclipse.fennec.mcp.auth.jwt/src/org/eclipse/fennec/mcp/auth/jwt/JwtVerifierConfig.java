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
package org.eclipse.fennec.mcp.auth.jwt;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration of a {@link JwtTokenVerifierComponent}. Factory configuration
 * — one instance per identity provider.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@ObjectClassDefinition(name = "MCP JWT Token Verifier",
		description = "Validates JWT bearer tokens offline against a JWKS endpoint (signature, issuer, audience, expiry)")
public @interface JwtVerifierConfig {

	@AttributeDefinition(name = "JWKS URL", description = "HTTPS URL of the identity provider's JSON Web Key Set, "
			+ "e.g. https://idp.example.org/realms/mcp/protocol/openid-connect/certs")
	String jwks_url();

	@AttributeDefinition(name = "Required issuer", description = "Exact 'iss' claim value a token must carry")
	String issuer();

	@AttributeDefinition(name = "Required audience", description = "Required 'aud' claim value; empty = audience not enforced")
	String audience() default "";

	@AttributeDefinition(name = "Allowed JWS algorithms", description = "Signature algorithms accepted for token verification")
	String[] allowed_algorithms() default { "RS256", "ES256" };

	@AttributeDefinition(name = "Max clock skew (seconds)", description = "Tolerated clock difference for 'exp'/'nbf' checks")
	int clock_skew_seconds() default 30;

	@AttributeDefinition(name = "Verifier name", description = "Value of the 'verifier.name' service property, "
			+ "used to select this verifier via the MCP server's 'verifier.target' filter")
	String verifier_name() default "jwt";
}
