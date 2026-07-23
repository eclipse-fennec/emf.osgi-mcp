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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import org.eclipse.fennec.mcp.api.auth.McpPrincipal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Verifies the offline JWT validation of {@link JwtTokenVerifierComponent}
 * against a local JWK set: signature, issuer, audience, expiry and claim
 * extraction — no network involved.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
class JwtTokenVerifierComponentTest {

	private static final String ISSUER = "https://idp.example.org/realms/mcp";

	private static RSAKey signingKey;
	private static RSAKey foreignKey;
	private static JWKSource<SecurityContext> jwkSource;

	@BeforeAll
	static void generateKeys() throws Exception {
		signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
		foreignKey = new RSAKeyGenerator(2048).keyID("k1").generate();
		jwkSource = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
	}

	private static JwtTokenVerifierComponent verifier(String audience) {
		return new JwtTokenVerifierComponent(
				JwtTokenVerifierComponent.createProcessor(jwkSource, config(audience)));
	}

	private static String token(RSAKey key, JWTClaimsSet claims) throws Exception {
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private static JWTClaimsSet.Builder validClaims() {
		return new JWTClaimsSet.Builder()
				.subject("client-a")
				.issuer(ISSUER)
				.expirationTime(Date.from(Instant.now().plusSeconds(300)));
	}

	@Test
	void validToken_yieldsPrincipalWithIdentityExpiryAndScopes() throws Exception {
		String token = token(signingKey, validClaims().claim("scope", "mcp:tools mcp:read").build());

		Optional<McpPrincipal> principal = verifier("").verify(token, null);

		assertThat(principal).isPresent();
		assertThat(principal.get().clientId()).isEqualTo("client-a");
		assertThat(principal.get().expiresAt()).isAfter(Instant.now());
		assertThat(principal.get().scopes()).containsExactly("mcp:tools", "mcp:read");
	}

	@Test
	void expiredToken_isRejected() throws Exception {
		String token = token(signingKey, validClaims()
				.expirationTime(Date.from(Instant.now().minusSeconds(600))).build());

		assertThat(verifier("").verify(token, null)).isEmpty();
	}

	@Test
	void wrongIssuer_isRejected() throws Exception {
		String token = token(signingKey, validClaims().issuer("https://evil.example.org").build());

		assertThat(verifier("").verify(token, null)).isEmpty();
	}

	@Test
	void foreignSignature_isRejected() throws Exception {
		String token = token(foreignKey, validClaims().build());

		assertThat(verifier("").verify(token, null)).isEmpty();
	}

	@Test
	void missingSubject_isRejected() throws Exception {
		String token = token(signingKey, new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.expirationTime(Date.from(Instant.now().plusSeconds(300))).build());

		assertThat(verifier("").verify(token, null)).isEmpty();
	}

	@Test
	void configuredAudience_isEnforced() throws Exception {
		String withoutAud = token(signingKey, validClaims().build());
		String withAud = token(signingKey, validClaims().audience("mcp-endpoint").build());

		assertThat(verifier("mcp-endpoint").verify(withoutAud, null)).isEmpty();
		assertThat(verifier("mcp-endpoint").verify(withAud, null)).isPresent();
	}

	@Test
	void garbageToken_isRejected() {
		assertThat(verifier("").verify("not-a-jwt", null)).isEmpty();
	}

	private static JwtVerifierConfig config(String audience) {
		return new JwtVerifierConfig() {
			@Override
			public Class<? extends Annotation> annotationType() {
				return JwtVerifierConfig.class;
			}

			@Override
			public String jwks_url() {
				return "https://unused.example.org/jwks";
			}

			@Override
			public String issuer() {
				return ISSUER;
			}

			@Override
			public String audience() {
				return audience;
			}

			@Override
			public String[] allowed_algorithms() {
				return new String[] { "RS256" };
			}

			@Override
			public int clock_skew_seconds() {
				return 5;
			}

			@Override
			public String verifier_name() {
				return "test";
			}
		};
	}
}
