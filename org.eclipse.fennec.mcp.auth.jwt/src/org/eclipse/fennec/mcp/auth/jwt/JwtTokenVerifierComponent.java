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

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.fennec.mcp.api.auth.McpPrincipal;
import org.eclipse.fennec.mcp.api.auth.McpTokenVerifier;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link McpTokenVerifier} validating JWT bearer tokens <b>offline</b>: the
 * signature is checked against the identity provider's JWKS (fetched and
 * cached by Nimbus, no per-request IdP round-trip), and the {@code iss},
 * {@code aud}, {@code exp}/{@code nbf} and {@code sub} claims are enforced.
 * The {@code sub} claim becomes the per-client identity of the returned
 * {@link McpPrincipal}; the {@code scope} claim (space-separated, as minted by
 * Keycloak and most OIDC providers) is exposed as scopes.
 * <p>
 * Factory configuration: create one instance per identity provider and select
 * it on the MCP server via {@code verifier.target=(verifier.name=...)}.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@Component(name = "JwtTokenVerifier", service = McpTokenVerifier.class, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = JwtVerifierConfig.class, factory = true)
public class JwtTokenVerifierComponent implements McpTokenVerifier {

	private static final Logger LOGGER = Logger.getLogger(JwtTokenVerifierComponent.class.getName());
	private static final String SCOPE_CLAIM = "scope";

	private volatile ConfigurableJWTProcessor<SecurityContext> processor;

	public JwtTokenVerifierComponent() {
		// default constructor for DS
	}

	/**
	 * Test constructor wiring a prepared processor (e.g. against a local JWK set).
	 */
	JwtTokenVerifierComponent(ConfigurableJWTProcessor<SecurityContext> processor) {
		this.processor = processor;
	}

	@Activate
	@Modified
	void activate(JwtVerifierConfig config) throws MalformedURLException {
		JWKSource<SecurityContext> jwkSource = JWKSourceBuilder
				.create(URI.create(config.jwks_url()).toURL())
				.build();
		this.processor = createProcessor(jwkSource, config);
		LOGGER.info(() -> String.format("JWT verifier '%s' active: issuer '%s', JWKS %s",
				config.verifier_name(), config.issuer(), config.jwks_url()));
	}

	/**
	 * Builds the JWT processor enforcing signature (allow-listed algorithms
	 * against the JWK source), exact issuer, optional audience, and required
	 * {@code sub}/{@code exp} claims with the configured clock skew.
	 */
	static ConfigurableJWTProcessor<SecurityContext> createProcessor(JWKSource<SecurityContext> jwkSource, JwtVerifierConfig config) {
		Set<JWSAlgorithm> algorithms = Arrays.stream(config.allowed_algorithms())
				.map(JWSAlgorithm::parse)
				.collect(Collectors.toUnmodifiableSet());
		DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, jwkSource));
		JWTClaimsSet exactMatchClaims = new JWTClaimsSet.Builder().issuer(config.issuer()).build();
		Set<String> requiredClaims = new HashSet<>(Set.of(JWTClaimNames.SUBJECT, JWTClaimNames.EXPIRATION_TIME, JWTClaimNames.ISSUER));
		String audience = config.audience();
		DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
				audience == null || audience.isBlank() ? null : audience,
				exactMatchClaims, requiredClaims);
		claimsVerifier.setMaxClockSkew(config.clock_skew_seconds());
		processor.setJWTClaimsSetVerifier(claimsVerifier);
		return processor;
	}

	@Override
	public Optional<McpPrincipal> verify(String bearerToken, HttpServletRequest request) {
		try {
			JWTClaimsSet claims = processor.process(bearerToken, null);
			Date expiration = claims.getExpirationTime();
			return Optional.of(new McpPrincipal(claims.getSubject(),
					expiration == null ? null : expiration.toInstant(),
					scopes(claims)));
		} catch (ParseException | BadJOSEException | JOSEException e) {
			LOGGER.log(Level.FINE, "Rejected JWT bearer token", e);
			return Optional.empty();
		}
	}

	private static List<String> scopes(JWTClaimsSet claims) {
		try {
			String scope = claims.getStringClaim(SCOPE_CLAIM);
			if (scope == null || scope.isBlank()) {
				return List.of();
			}
			return List.of(scope.trim().split("\\s+"));
		} catch (ParseException e) {
			// a non-string scope claim is ignored, not a verification failure
			return List.of();
		}
	}
}
