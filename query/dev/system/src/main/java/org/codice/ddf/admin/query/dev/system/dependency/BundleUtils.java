/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.admin.query.dev.system.dependency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;
import org.apache.karaf.bundle.core.BundleService;
import org.codice.ddf.admin.api.fields.ListField;
import org.codice.ddf.admin.query.dev.system.discover.GetBundles;
import org.codice.ddf.admin.query.dev.system.fields.BundleField;
import org.codice.ddf.admin.query.dev.system.fields.PackageField;
import org.codice.ddf.admin.query.dev.system.fields.ServiceField;
import org.codice.ddf.admin.query.dev.system.fields.ServiceReferenceField;
import org.codice.ddf.admin.query.dev.system.fields.ServiceReferenceListField;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.blueprint.container.BlueprintContainer;
import org.osgi.service.blueprint.reflect.ComponentMetadata;
import org.osgi.service.blueprint.reflect.ReferenceListMetadata;
import org.osgi.service.blueprint.reflect.ReferenceMetadata;
import org.osgi.service.blueprint.reflect.ServiceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BundleUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(BundleUtils.class);
  public static final String BLUEPRINT_BUNDLE_IDENTIFIER = "org.osgi.service.blueprint.container.BlueprintContainer";
  private static final String EXPORT_PACKAGE_HEADER = "Export-Package";
  private BundleService bundleService;

  public BundleUtils(BundleService bundleService) {
    this.bundleService = bundleService;
  }

  public ListField<BundleField> getAllBundleFields() {
    return getBundles(null);
  }

  public ListField<BundleField> getBundles(List<Integer> bundleIds) {
    List<BundleField> bundlesFields = new ArrayList<>();
    List<Bundle> bundles =
        Arrays.asList(getBundleContext().getBundles());

    if (CollectionUtils.isNotEmpty(bundleIds)) {
      bundles =
          bundles
              .stream()
              .filter(bundle -> bundleIds.contains(((Long) bundle.getBundleId()).intValue()))
              .collect(Collectors.toList());
    }

    for (Bundle bundle : bundles) {
      BundleField newBundleField =
          new BundleField()
              .bundleName(bundle.getSymbolicName())
              .id(Math.toIntExact(bundle.getBundleId()))
              .location(bundle.getLocation());

      populatePackages(bundle, newBundleField);
      populateServices(bundle, newBundleField);
      bundlesFields.add(newBundleField);
    }

    return new BundleField.ListImpl().addAll(bundlesFields);
  }

  private void populatePackages(Bundle bundle, BundleField toPopulate) {
    for (Map.Entry<String, Bundle> pkgDep : bundleService.getWiredBundles(bundle).entrySet()) {
      toPopulate.addImportedPackage(
          new PackageField()
              .pkgName(pkgDep.getKey())
              .bundleId(Math.toIntExact(pkgDep.getValue().getBundleId())));
    }

    for (Clause clause : Parser.parseHeader(bundle.getHeaders().get(EXPORT_PACKAGE_HEADER))) {
      toPopulate.addExportedPackage(
          new PackageField()
              .pkgName(clause.getName())
              .bundleId(Math.toIntExact(bundle.getBundleId())));
    }
  }

  private void populateServices(Bundle bundle, BundleField toPopulate) {
    Optional<BlueprintContainer> blueprintContainer = getBlueprintService(bundle);
    if (blueprintContainer.isPresent()) {
      List<ComponentMetadata> cmpMetas =
          blueprintContainer
              .get()
              .getComponentIds()
              .stream()
              .map(id -> blueprintContainer.get().getComponentMetadata(id))
              .collect(Collectors.toList());

      for (ComponentMetadata meta : cmpMetas) {
        if (meta instanceof ReferenceListMetadata) {
          populateServiceRefLists((ReferenceListMetadata) meta, bundle, toPopulate);
        } else if (meta instanceof ReferenceMetadata) {
          populateServiceRef((ReferenceMetadata) meta, bundle, toPopulate);
        } else if (meta instanceof ServiceMetadata) {
          ServiceReference[] refs = bundle.getRegisteredServices();

          if (refs == null) {
            continue;
          }

          Arrays.stream(refs).forEach(ref -> toPopulate.addService(new ServiceField(ref)));
        }
      }
    }
  }

  private Optional<BlueprintContainer> getBlueprintService(Bundle bundle) {
    if (bundle.getRegisteredServices() == null || bundle.getRegisteredServices().length == 0) {
      return Optional.empty();
    }

    return Arrays.stream(bundle.getRegisteredServices())
        .filter(
            ref ->
                ref.toString().contains(BLUEPRINT_BUNDLE_IDENTIFIER))
        .map(
            ref -> bundle.getBundleContext().getService((ServiceReference<BlueprintContainer>) ref))
        .findFirst();
  }

  private void populateServiceRefLists(
      ReferenceListMetadata refListMeta, Bundle bundle, BundleField toPopulate) {
    ServiceReferenceListField refListF = new ServiceReferenceListField();
    String searchFilter = formatFilter(refListMeta.getFilter());

    refListF
        .filter(searchFilter)
        .referenceListInterface(refListMeta.getInterface())
        .resolution(refListMeta.getAvailability());

    ServiceReference[] refs = null;

    try {
      refs =
          bundle.getBundleContext().getServiceReferences(refListMeta.getInterface(), searchFilter);

    } catch (InvalidSyntaxException e) {
      LOGGER.warn(
          "Failed to parse filter for bundle {} during service reference look up. Filter was {}.",
          bundle.getSymbolicName(),
          searchFilter);
    }

    if (refs == null) {
      return;
    }

    Arrays.stream(refs).forEach(ref -> refListF.addService(new ServiceField(ref)));
    toPopulate.addServiceRefList(refListF);
  }

  private void populateServiceRef(
      ReferenceMetadata refMeta, Bundle bundle, BundleField toPopulate) {
    String searchFilter = formatFilter(refMeta.getFilter());

    ServiceReference[] refs = null;
    try {
      refs = bundle.getBundleContext().getServiceReferences(refMeta.getInterface(), searchFilter);
    } catch (InvalidSyntaxException e) {
      LOGGER.warn(
          "Failed to parse filter for bundle {} during service reference look up. Filter was {}.",
          bundle.getSymbolicName(),
          searchFilter);
    }

    if (refs == null) {
      return;
    }

    for (ServiceReference ref : refs) {
      toPopulate.addServiceRef(
          new ServiceReferenceField()
              .serviceInterface(refMeta.getInterface())
              .filter(searchFilter)
              .resolution(refMeta.getAvailability())
              .service(new ServiceField(ref)));
    }
  }

  public static Optional<BundleField> getBundleById(List<BundleField> bundles, int id) {
    return bundles.stream().filter(bundle -> bundle.id().equals(id)).findFirst();
  }

  private String formatFilter(String filter) {
    if (filter == null || filter.startsWith("(")) {
      return filter;
    } else {
      return "(" + filter + ")";
    }
  }

  private BundleContext getBundleContext() {
    return FrameworkUtil.getBundle(GetBundles.class).getBundleContext();
  }
}
