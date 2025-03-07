/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.kubernetes.fabric8.config;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.kubernetes.commons.config.ConfigMapPrefixContext;
import org.springframework.cloud.kubernetes.commons.config.NamedConfigMapNormalizedSource;
import org.springframework.cloud.kubernetes.commons.config.NormalizedSource;
import org.springframework.cloud.kubernetes.commons.config.SourceData;
import org.springframework.core.env.Environment;

import static org.springframework.cloud.kubernetes.commons.config.ConfigUtils.getApplicationName;
import static org.springframework.cloud.kubernetes.commons.config.ConfigUtils.onException;
import static org.springframework.cloud.kubernetes.commons.config.Constants.PROPERTY_SOURCE_NAME_SEPARATOR;
import static org.springframework.cloud.kubernetes.fabric8.config.Fabric8ConfigUtils.getConfigMapData;

/**
 * Provides an implementation of {@link Fabric8ContextToSourceData} for a named config
 * map.
 *
 * @author wind57
 */
final class NamedConfigMapContextToSourceDataProvider implements Supplier<Fabric8ContextToSourceData> {

	private static final Log LOG = LogFactory.getLog(NamedConfigMapContextToSourceDataProvider.class);

	private final BiFunction<Map<String, String>, Environment, Map<String, Object>> entriesProcessor;

	private final BiFunction<String, String, String> sourceNameMapper;

	private final Function<ConfigMapPrefixContext, SourceData> withPrefix;

	private NamedConfigMapContextToSourceDataProvider(
			BiFunction<Map<String, String>, Environment, Map<String, Object>> entriesProcessor,
			BiFunction<String, String, String> sourceNameMapper,
			Function<ConfigMapPrefixContext, SourceData> withPrefix) {
		this.entriesProcessor = Objects.requireNonNull(entriesProcessor);
		this.sourceNameMapper = Objects.requireNonNull(sourceNameMapper);
		this.withPrefix = Objects.requireNonNull(withPrefix);
	}

	static NamedConfigMapContextToSourceDataProvider of(
			BiFunction<Map<String, String>, Environment, Map<String, Object>> entriesProcessor,
			BiFunction<String, String, String> sourceNameMapper,
			Function<ConfigMapPrefixContext, SourceData> withPrefix) {
		return new NamedConfigMapContextToSourceDataProvider(entriesProcessor, sourceNameMapper, withPrefix);
	}

	/*
	 * Computes a ContextToSourceData (think content) for config map(s) based on name.
	 * There could be potentially many config maps read (we also read profile based
	 * sources). In such a case the name of the property source is going to be the
	 * concatenated config map names, while the value is all the data that those config
	 * maps hold.
	 */
	@Override
	public Fabric8ContextToSourceData get() {

		return context -> {

			NamedConfigMapNormalizedSource source = (NamedConfigMapNormalizedSource) context.normalizedSource();
			String namespace = context.namespace();
			String initialConfigMapName = appName(context.environment(), source).get();
			String currentConfigMapName = initialConfigMapName;
			Set<String> propertySourceNames = new LinkedHashSet<>();
			propertySourceNames.add(initialConfigMapName);

			Map<String, Object> result = new HashMap<>();

			LOG.info("Loading ConfigMap with name '" + initialConfigMapName + "' in namespace '" + namespace + "'");
			try {
				Map<String, String> data = getConfigMapData(context.client(), namespace, currentConfigMapName);
				result.putAll(entriesProcessor.apply(data, context.environment()));

				if (context.environment() != null && source.profileSpecificSources()) {
					for (String activeProfile : context.environment().getActiveProfiles()) {
						currentConfigMapName = initialConfigMapName + "-" + activeProfile;
						Map<String, String> dataWithProfile = getConfigMapData(context.client(), namespace,
								currentConfigMapName);
						if (!dataWithProfile.isEmpty()) {
							propertySourceNames.add(currentConfigMapName);
							result.putAll(entriesProcessor.apply(dataWithProfile, context.environment()));
						}
					}
				}

				if (!"".equals(source.prefix())) {
					ConfigMapPrefixContext prefixContext = new ConfigMapPrefixContext(result, source.prefix(),
							namespace, propertySourceNames);
					return withPrefix.apply(prefixContext);
				}

			}
			catch (Exception e) {
				String message = "Unable to read ConfigMap with name '" + currentConfigMapName + "' in namespace '"
						+ namespace + "'";
				onException(source.failFast(), message, e);
			}

			String names = String.join(PROPERTY_SOURCE_NAME_SEPARATOR, propertySourceNames);
			return new SourceData(sourceNameMapper.apply(names, namespace), result);
		};

	}

	private Supplier<String> appName(Environment environment, NormalizedSource normalizedSource) {
		return () -> getApplicationName(environment, normalizedSource.name().orElse(null), normalizedSource.target());
	}

}
