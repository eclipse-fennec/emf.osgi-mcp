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
package org.eclipse.fennec.mcp.emf.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetLimits;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.TestModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code export_package}: the full-fidelity metamodel read. Covers what
 * {@code describe_eclass} structurally cannot report — annotations, abstract
 * classes and cross-package supertypes — plus the allow-list behaviour and the
 * {@code import_ecore} round trip.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
class ExportPackageToolTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String SESSION = "session-1";

	@TempDir
	Path workDir;

	private EPackage library;
	private EPackage uplink;
	private EPackage.Registry packageRegistry;
	private McpAsyncServerExchange exchange;
	private PackageRegistry sessionPackages;
	private DatasetRegistry datasets;

	@BeforeEach
	void setUp() throws Exception {
		library = TestModels.libraryPackage();
		uplink = TestModels.annotatedPackage(library);
		packageRegistry = TestModels.registryWith(library, uplink);
		exchange = mock(McpAsyncServerExchange.class);
		when(exchange.sessionId()).thenReturn(SESSION);
		sessionPackages = packageRegistryFor(Set.of("*"), Set.of(), 100);
		datasets = datasetRegistryFor(DatasetLimits.defaults());
	}

	@Test
	void annotationsSurviveVerbatim() {
		String ecore = content(exportWith(fullGuard()), TestModels.UPLINK_NS_URI);

		assertThat(ecore)
				.contains(TestModels.TYPE_MAPPING_SOURCE)
				.contains("typeDiscriminatorPath")
				.contains("deviceInfo.deviceProfileName")
				.contains("typeDiscriminator")
				.contains("Sensor_A")
				.contains(TestModels.EXTENDED_META_DATA_SOURCE)
				.contains("BatV");
	}

	@Test
	void abstractClassesAppearAtAll() {
		String ecore = content(exportWith(fullGuard()), TestModels.UPLINK_NS_URI);

		assertThat(ecore).contains("name=\"UplinkBase\"");
		assertThat(ecore).contains("abstract=\"true\"");
	}

	@Test
	void aForeignSuperTypeIsAnAbsoluteNsUriReference() {
		String ecore = content(exportWith(fullGuard()), TestModels.UPLINK_NS_URI);

		// One attribute holds both supertypes: the local one compact, the foreign one
		// absolute. EMF deresolves against the resource URI by default, which would
		// leave the foreign one as a bare relative segment ('library#//AbstractItem')
		// that no importer can resolve back to a namespace.
		assertThat(ecore).contains("eSuperTypes=\"#//UplinkBase " + TestModels.ABSTRACT_ITEM + "\"");
		// the referenced package is a reference, never inlined
		assertThat(ecore).doesNotContain("name=\"Book\"");
	}

	@Test
	void referencesInsideTheDocumentKeepTheEcoreFragmentForm() {
		String ecore = content(exportWith(fullGuard()), TestModels.NS_URI);

		// suppressing deresolution wholesale to keep foreign references absolute
		// would spell every local reference as a full absolute URI instead
		assertThat(ecore).contains("eType=\"#//Book\"");
		assertThat(ecore).doesNotContain("http://example.org/library#//Book");
	}

	@Test
	void aSelfContainedPackageRoundTripsThroughImportEcore() {
		String ecore = content(exportWith(fullGuard()), TestModels.NS_URI);

		ImportEcoreTool importEcore = new ImportEcoreTool();
		importEcore.registry = datasets;
		importEcore.packages = sessionPackages;
		importEcore.activate();

		Map<String, Object> imported = call(importEcore, Map.of("xmi", ecore));
		assertThat(imported.get("datasetId")).isNotNull();
		assertThat(sessionPackages.resolve(SESSION, TestModels.NS_URI)).isNotNull();
		assertThat(sessionPackages.resolve(SESSION, TestModels.NS_URI).getEClassifier("AbstractItem")).isNotNull();
	}

	@Test
	void aDeniedAnnotationSourceMakesTheWholePackageUnexportable() {
		String error = callExpectingError(exportWith(fullGuard(), denying(TestModels.TYPE_MAPPING_SOURCE)),
				Map.of("nsURI", TestModels.UPLINK_NS_URI));

		// A .ecore carries every annotation and cannot be filtered without ceasing
		// to be the package, so the export is refused rather than silently stripped
		// - the same all-or-nothing rule the class allow-list already applies here.
		assertThat(error).contains("withheld by the deployment").contains("describe_eclass");
		// counted, never named
		assertThat(error).doesNotContain(TestModels.TYPE_MAPPING_SOURCE);
	}

	@Test
	void aPackageWithoutTheDeniedSourceStillExports() {
		String ecore = content(exportWith(fullGuard(), denying("http://example.org/nothing/here/*")),
				TestModels.UPLINK_NS_URI);

		assertThat(ecore).contains(TestModels.TYPE_MAPPING_SOURCE);
	}

	@Test
	void aSessionPackageIsExportedEvenWithADeniedSource() {
		// The agent authored or imported this package itself, so withholding it
		// from the agent protects nothing - the same reason the allow-list is not
		// re-checked for session packages.
		sessionPackages.register(SESSION, TestModels.annotatedPackage(TestModels.libraryPackage()));
		ExportPackageTool tool = exportWith(guardFor(Set.of(), Set.of()),
				denying(TestModels.TYPE_MAPPING_SOURCE));

		Map<String, Object> result = call(tool, Map.of("nsURI", TestModels.UPLINK_NS_URI));

		assertThat(result).containsEntry("origin", "session");
		assertThat(String.valueOf(result.get("content"))).contains(TestModels.TYPE_MAPPING_SOURCE);
	}

	@Test
	void aSessionRegisteredPackageIsExportableWithoutAnyAllowList() {
		sessionPackages.register(SESSION, TestModels.libraryPackage());
		// deny-all guard: the session package must not need an allow-list entry
		ExportPackageTool tool = exportWith(guardFor(Set.of(), Set.of()));

		Map<String, Object> result = call(tool, Map.of("nsURI", TestModels.NS_URI));

		assertThat(result).containsEntry("origin", "session");
		assertThat(String.valueOf(result.get("content"))).contains("name=\"library\"");
	}

	@Test
	void aDeniedPackageIsRefusedBeforeTheRegistryIsTouched() {
		String error = callExpectingError(exportWith(guardFor(Set.of(), Set.of())),
				Map.of("nsURI", TestModels.UPLINK_NS_URI));

		assertThat(error).contains("not allow-listed");
		// nothing about whether such a package exists, or what is in it
		assertThat(error).doesNotContain("UplinkBase").doesNotContain("registered in this runtime");
	}

	@Test
	void aPartiallyAllowListedPackageIsRefusedByCountNotByName() {
		ModelGuard partial = guardFor(Set.of(TestModels.UPLINK_NS_URI), Set.of(TestModels.UPLINK_A));

		String error = callExpectingError(exportWith(partial), Map.of("nsURI", TestModels.UPLINK_NS_URI));

		assertThat(error)
				.contains("1 of its 2 EClasses are not allow-listed")
				.contains("eclass.allowlist");
		// the withheld class is counted, never named
		assertThat(error).doesNotContain("UplinkBase");
	}

	@Test
	void anUnknownFormatIsRejected() {
		String error = callExpectingError(exportWith(fullGuard()),
				Map.of("nsURI", TestModels.NS_URI, "format", "json"));

		assertThat(error).contains("A .ecore document is XMI");
	}

	private static AnnotationVisibility denying(String... sourcePatterns) {
		return AnnotationVisibility.denying(List.of(sourcePatterns), List.of());
	}

	private ExportPackageTool exportWith(ModelGuard guard) {
		return exportWith(guard, AnnotationVisibility.unrestricted());
	}

	private ExportPackageTool exportWith(ModelGuard guard, AnnotationVisibility visibility) {
		ExportPackageTool tool = new ExportPackageTool();
		tool.guard = guard;
		tool.visibility = visibility;
		tool.packages = sessionPackages;
		tool.registry = datasets;
		tool.activate();
		return tool;
	}

	private ModelGuard fullGuard() {
		Set<String> classes = java.util.stream.Stream.of(library, uplink)
				.flatMap(p -> p.getEClassifiers().stream())
				.filter(EClass.class::isInstance)
				.map(EClass.class::cast)
				.map(ModelGuard::refOf)
				.collect(Collectors.toSet());
		return guardFor(Set.of(TestModels.NS_URI, TestModels.UPLINK_NS_URI), classes);
	}

	private ModelGuard guardFor(Set<String> packages, Set<String> classes) {
		try {
			var constructor = ModelGuard.class.getDeclaredConstructor(EPackage.Registry.class, Set.class, Set.class);
			constructor.setAccessible(true);
			return constructor.newInstance(packageRegistry, packages, classes);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static PackageRegistry packageRegistryFor(Set<String> allow, Set<String> deny, int max) throws Exception {
		var constructor = PackageRegistry.class.getDeclaredConstructor(Set.class, Set.class, int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(allow, deny, max);
	}

	private DatasetRegistry datasetRegistryFor(DatasetLimits limits) throws Exception {
		ResourceSetFactory factory = () -> {
			ResourceSetImpl resourceSet = new ResourceSetImpl();
			resourceSet.setPackageRegistry(new EPackageRegistryImpl(packageRegistry));
			return resourceSet;
		};
		var constructor = DatasetRegistry.class.getDeclaredConstructor(ResourceSetFactory.class, DatasetLimits.class, Path.class);
		constructor.setAccessible(true);
		return constructor.newInstance(factory, limits, workDir);
	}

	private String content(ExportPackageTool tool, String nsURI) {
		return String.valueOf(call(tool, Map.of("nsURI", nsURI)).get("content"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> call(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		String text = ((McpSchema.TextContent) result.content().get(0)).text();
		assertThat(result.isError()).as("tool error: %s", text).isNotEqualTo(Boolean.TRUE);
		return MAPPER.readValue(text, Map.class);
	}

	private String callExpectingError(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}
}
