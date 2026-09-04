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
package org.eclipse.fennec.mcp.api;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration of {@link AnnotationVisibility}. Both lists speak
 * {@link UriPatterns}: an exact entry, a {@code prefix*}, or a bare {@code *}.
 * <p>
 * Both default to empty, which denies nothing — the policy is opt-in, so adding
 * this PID to a deployment that has none changes no behaviour until an entry is
 * written. That is the opposite default from the model guard's allow-lists, and
 * deliberately so; {@link AnnotationVisibility} explains why annotations get a
 * deny-list where packages and classes get allow-lists.
 *
 * @author ilenia
 * @since Sep 3, 2026
 */
@ObjectClassDefinition(name = "MCP Annotation Visibility", description = "EAnnotation sources and metadata aspect types withheld from agents")
public @interface AnnotationVisibilityConfig {

	@AttributeDefinition(required = false, name = "Denied annotation sources", description = "EAnnotation source URIs never shown to an agent. Exact, 'prefix*' or '*'. Empty denies nothing. A denied source is withheld from describe_eclass, refuses a find_*_by_annotation query naming it, is omitted from list_annotation_sources, and makes export_package refuse a package carrying it - a .ecore is the whole package and cannot be filtered.")
	String[] annotation_source_denylist() default {};

	@AttributeDefinition(required = false, name = "Denied aspect types", description = "Metadata aspect type ids never shown to an agent, e.g. 'codec'. Exact, 'prefix*' or '*'. Empty denies nothing. An aspect carries no annotation source, so denying a source does NOT hide the aspect parsed from it: keep this list consistent with the source list or describe_aspects hands back what the source list withholds.")
	String[] aspect_type_denylist() default {};
}
