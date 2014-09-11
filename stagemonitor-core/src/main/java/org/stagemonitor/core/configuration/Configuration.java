package org.stagemonitor.core.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public class Configuration {

	private final Logger logger = LoggerFactory.getLogger(getClass());
	private final String updateConfigPasswordKey;
	private final List<ConfigurationSource> configurationSources = new LinkedList<ConfigurationSource>();

	private Map<Class<? extends ConfigurationOptionProvider>, ConfigurationOptionProvider> pluginConfiguration = new HashMap<Class<? extends ConfigurationOptionProvider>, ConfigurationOptionProvider>();
	private Map<String, ConfigurationOption<?>> configurationOptionsByKey = new LinkedHashMap<String, ConfigurationOption<?>>();
	private Map<String, List<ConfigurationOption<?>>> configurationOptionsByPlugin = new LinkedHashMap<String, List<ConfigurationOption<?>>>();

	/**
	 * @param updateConfigPasswordKey the key of the password to update configuration settings.
	 *                                The actual password is loaded from the configuration sources. Set to null to disable dynamic updates.
	 */
	public Configuration(String updateConfigPasswordKey) {
		this(ConfigurationOptionProvider.class, updateConfigPasswordKey);
	}

	/**
	 * @param optionProviderClass     the class that should be used to lookup instances of
	 *                                {@link ConfigurationOptionProvider} via {@link ServiceLoader#load(Class)}
	 */
	public Configuration(Class<? extends ConfigurationOptionProvider> optionProviderClass) {
		this(optionProviderClass, null);
	}

	/**
	 * @param optionProviderClass     the class that should be used to lookup instances of
	 *                                {@link ConfigurationOptionProvider} via {@link ServiceLoader#load(Class)}
	 * @param updateConfigPasswordKey the key of the password to update configuration settings.
	 *                                The actual password is loaded from the configuration sources. Set to null to disable dynamic updates.
	 */
	public Configuration(Class<? extends ConfigurationOptionProvider> optionProviderClass, String updateConfigPasswordKey) {
		this(optionProviderClass, Collections.<ConfigurationSource>emptyList(), updateConfigPasswordKey);
	}

	/**
	 * @param optionProviderClass     the class that should be used to lookup instances of
	 *                                {@link ConfigurationOptionProvider} via {@link ServiceLoader#load(Class)}
	 * @param updateConfigPasswordKey the key of the password to update configuration settings.
	 *                                The actual password is loaded from the configuration sources. Set to null to disable dynamic updates.
	 */
	public Configuration(Class<? extends ConfigurationOptionProvider> optionProviderClass,
						 List<ConfigurationSource> configSources, String updateConfigPasswordKey) {
		this(ServiceLoader.load(optionProviderClass), configSources, updateConfigPasswordKey);
	}

	public Configuration(Iterable<? extends ConfigurationOptionProvider> optionProviders,
						 List<ConfigurationSource> configSources, String updateConfigPasswordKey) {
		this.updateConfigPasswordKey = updateConfigPasswordKey;
		configurationSources.addAll(configSources);
		registerConfigurationOptions(optionProviders);
	}

	private void registerConfigurationOptions(Iterable<? extends ConfigurationOptionProvider> optionProviders) {
		for (ConfigurationOptionProvider configurationOptionProvider : optionProviders) {
			try {
				registerPluginConfiguration(configurationOptionProvider);
			} catch (RuntimeException e) {
				logger.warn("Error while initializing configuration options for " +
						configurationOptionProvider.getClass() + " (this exception is ignored)", e);
			}
		}
	}

	private void registerPluginConfiguration(ConfigurationOptionProvider configurationOptionProvider) {
		pluginConfiguration.put(configurationOptionProvider.getClass(), configurationOptionProvider);
		for (ConfigurationOption<?> configurationOption : configurationOptionProvider.getConfigurationOptions()) {
			add(configurationOption);
		}
	}

	public <T extends ConfigurationOptionProvider> T getConfig(Class<T> configClass) {
		return (T) pluginConfiguration.get(configClass);
	}

	private void add(final ConfigurationOption<?> configurationOption) {
		configurationOption.setConfigurationSources(configurationSources);

		configurationOptionsByKey.put(configurationOption.getKey(), configurationOption);
		addConfigurationOptionByPlugin(configurationOption.getPluginName(), configurationOption);
	}

	private void addConfigurationOptionByPlugin(String pluginName, final ConfigurationOption<?> configurationOption) {
		if (configurationOptionsByPlugin.containsKey(pluginName)) {
			configurationOptionsByPlugin.get(pluginName).add(configurationOption);
		} else {
			configurationOptionsByPlugin.put(pluginName, new ArrayList<ConfigurationOption<?>>() {{
				add(configurationOption);
			}});
		}
	}

	public Map<String, List<ConfigurationOption<?>>> getConfigurationOptionsByPlugin() {
		return Collections.unmodifiableMap(configurationOptionsByPlugin);
	}

	public Map<String, ConfigurationOption<?>> getConfigurationOptionsByKey() {
		return Collections.unmodifiableMap(configurationOptionsByKey);
	}

	public Map<String, Boolean> getNamesOfConfigurationSources() {
		final Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
		for (ConfigurationSource configurationSource : configurationSources) {
			result.put(configurationSource.getName(), configurationSource.isSavingPossible());
		}
		return result;
	}

	public ConfigurationOption<?> getConfigurationOptionByKey(String key) {
		return configurationOptionsByKey.get(key);
	}

	public void reload(String key) {
		if (configurationOptionsByKey.containsKey(key)) {
			configurationOptionsByKey.get(key).reload();
		}
	}

	public void reload() {
		for (ConfigurationSource configurationSource : configurationSources) {
			configurationSource.reload();
		}
		for (ConfigurationOption<?> configurationOption : configurationOptionsByKey.values()) {
			configurationOption.reload();
		}
	}

	public void addConfigurationSource(ConfigurationSource configurationSource) {
		addConfigurationSource(configurationSource, true);
	}

	public void addConfigurationSource(ConfigurationSource configurationSource, boolean firstPrio) {
		if (configurationSource == null) {
			return;
		}
		if (firstPrio) {
			configurationSources.add(0, configurationSource);
		} else {
			configurationSources.add(configurationSource);
		}
		reload();
	}

	/**
	 * Dynamically updates a configuration key.
	 *
	 * @param key                         the configuration key
	 * @param value                       the configuration value
	 * @param configurationSourceName     the {@link org.stagemonitor.core.configuration.ConfigurationSource#getName()}
	 *                                    of the configuration source the value should be stored to
	 * @param configurationUpdatePassword the password (must not be null)
	 * @throws IOException                   if there was an error saving the key to the source
	 * @throws IllegalStateException         if the update configuration password did not match
	 * @throws IllegalArgumentException      if there was a error processing the configuration key or value or the
	 *                                       configurationSourceName did not match any of the available configuration
	 *                                       sources
	 * @throws UnsupportedOperationException if saving values is not possible with this configuration source
	 */
	public void save(String key, String value, String configurationSourceName, String configurationUpdatePassword) throws IOException,
			IllegalArgumentException, IllegalStateException, UnsupportedOperationException {
		if (configurationUpdatePassword == null) {
			configurationUpdatePassword = "";
		}
		String updateConfigPassword = getString(updateConfigPasswordKey);
		if (updateConfigPassword == null) {
			throw new IllegalStateException("Update configuration password is not set. " +
					"Dynamic configuration changes are therefore not allowed.");
		}

		if (updateConfigPassword.equals(configurationUpdatePassword)) {
			final ConfigurationOption<?> configurationOption = validateConfigurationOption(key, value);
			saveToConfigurationSource(key, value, configurationSourceName, configurationOption);
		} else {
			throw new IllegalStateException("Wrong password for updating configuration.");
		}
	}

	private ConfigurationOption<?> validateConfigurationOption(String key, String value) throws IllegalArgumentException {
		final ConfigurationOption configurationOption = getConfigurationOptionByKey(key);
		if (configurationOption != null) {
			configurationOption.assertValid(value);
			return configurationOption;
		} else {
			throw new IllegalArgumentException("Config key '" + key + "' does not exist.");
		}
	}

	private void saveToConfigurationSource(String key, String value, String configurationSourceName, ConfigurationOption<?> configurationOption) throws IOException {
		for (ConfigurationSource configurationSource : configurationSources) {
			if (configurationSource.getName().equals(configurationSourceName)) {
				validateConfigurationSource(configurationSource, configurationOption);
				configurationSource.save(key, value);
				reload(key);
				logger.info("Updated configuration: {}={} ({})", key, value, configurationSourceName);
				return;
			}
		}
		throw new IllegalArgumentException("Configuration source '"+configurationSourceName+"' does not exist.");
	}

	private void validateConfigurationSource(ConfigurationSource configurationSource, ConfigurationOption<?> configurationOption) {
		if (!configurationOption.isDynamic() && !configurationSource.isSavingPersistent()) {
			throw new IllegalArgumentException("Non dynamic options can't be saved to a transient configuration source.");
		}
	}

	private String getString(String key) {
		String property = null;
		for (ConfigurationSource configurationSource : configurationSources) {
			property = configurationSource.getValue(key);
			if (property != null) {
				break;
			}
		}
		return property;
	}


}