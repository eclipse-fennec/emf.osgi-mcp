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

import java.util.List;
import java.util.logging.Logger;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

/**
 * The deployed {@link AnnotationVisibility} policy.
 * <p>
 * Its configuration policy is <b>optional</b> on purpose. The tools that read
 * annotations bind this service mandatorily, so a component that required
 * configuration would leave a runtime without the {@code MCPAnnotationVisibility}
 * PID with no service — and therefore with no annotation tools at all, rather
 * than with unrestricted ones. Absent configuration means both lists are empty,
 * which denies nothing.
 *
 * @author ilenia
 * @since Sep 3, 2026
 */
@Component(name = "MCPAnnotationVisibility", configurationPid = "MCPAnnotationVisibility", service = AnnotationVisibility.class)
@Designate(ocd = AnnotationVisibilityConfig.class)
public class AnnotationVisibilityComponent implements AnnotationVisibility {

	private static final Logger LOGGER = Logger.getLogger(AnnotationVisibilityComponent.class.getName());

	private volatile AnnotationVisibility delegate = AnnotationVisibility.unrestricted();

	@Activate
	void activate(AnnotationVisibilityConfig config) {
		update(config);
	}

	@Modified
	void update(AnnotationVisibilityConfig config) {
		List<String> sources = List.of(config.annotation_source_denylist());
		List<String> aspectTypes = List.of(config.aspect_type_denylist());
		this.delegate = AnnotationVisibility.denying(sources, aspectTypes);
		LOGGER.info(() -> String.format(
				"MCP annotation visibility updated: %d denied annotation source pattern(s), %d denied aspect type pattern(s)",
				sources.size(), aspectTypes.size()));
	}

	@Override
	public boolean isSourceVisible(String annotationSource) {
		return delegate.isSourceVisible(annotationSource);
	}

	@Override
	public boolean isAspectTypeVisible(String aspectTypeId) {
		return delegate.isAspectTypeVisible(aspectTypeId);
	}

	@Override
	public boolean isUnrestricted() {
		return delegate.isUnrestricted();
	}
}
