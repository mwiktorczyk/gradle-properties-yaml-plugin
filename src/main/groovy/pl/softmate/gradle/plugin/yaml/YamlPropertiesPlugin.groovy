/**
 * <p>Copyright (C) 2015 Mariusz Wiktorczyk</p>
 *
 * <p>This software is licensed under MIT (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <a href="https://opensource.org/licenses/MIT">https://opensource.org/licenses/MIT</a></p>
 *
 * <p>Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.</p> *
 */
package pl.softmate.gradle.plugin.yaml

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.plugins.PluginAware
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertyResolver
import org.springframework.core.env.PropertySource
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.yaml.snakeyaml.Yaml

import java.nio.file.Paths

/**
 * This is the main class for YAML properties plugin.
 * Please read project README for detailed description.
 *
 * <p>
 * Special thanks to Steven C. Saliman for his {@code gradle-properties-plugin}.
 * </p>
 *
 * @author Mariusz Wiktorczyk
 */
class YamlPropertiesPlugin implements Plugin<PluginAware> {

    /**
     *
     * @param pluginAware the object to which this plugin should be applied.  Currently,
     * this must be either a {@link org.gradle.api.Project Project} or {@link org.gradle.api.initialization.Settings Settings} object.
     *
     * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/initialization/Settings.html">Settings</a>
     * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/Project.html">Project</a>
     * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/StartParameter.html">StartParameter</a>
     * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/plugins/ExtraPropertiesExtension.html">ExtraPropertiesExtension</a>
     */
    @Override
    void apply(PluginAware pluginAware) {

        // Init main objects
        Gradle gradle
        Project currentProject = null
        if (pluginAware instanceof Project) {
            gradle = ((Project) pluginAware).gradle
            currentProject = ((Project) pluginAware)
            registerTaskListener(currentProject)
        } else if (pluginAware instanceof Settings) {
            gradle = ((Settings) pluginAware).gradle
        } else {
            throw new IllegalArgumentException("${pluginAware.getClass()} is currently not supported as apply target!")
        }

        // Grab start-up parameters
        def startParameters = [:]
        def profiles = [:]
        collectStartParametersAndActiveProfiles(gradle, startParameters, profiles)

        /*
         Add filterTokens to project extensions.
         Could be used laater on in Gradle script for Ant-style file/property filtering
        */
        pluginAware.ext.filterTokens = [:]

        /*
          Setup paths to all possible property files
         */
        def gradleMainPropFile = Paths.get(gradle.gradleUserHomeDir.absolutePath, 'gradle.yml').toFile()
        def gradleProjectPropFile = Paths.get(gradle.gradleUserHomeDir.absolutePath, "${gradle.rootProject.name}.yml").toFile()
        def rootProjectPropFile = Paths.get(gradle.rootProject.projectDir.absolutePath, 'gradle.yml').toFile()
        def currentProjectPropFile = null
        if (currentProject && gradle.rootProject.projectDir != currentProject.projectDir) {
            currentProjectPropFile = Paths.get(currentProject.projectDir.absolutePath, 'gradle.yml').toFile()
        }

        /*
          Main processing
         */
        processEnvironmentProperties pluginAware
        processSystemProperties pluginAware
        processStartParameterProperties pluginAware, gradle.startParameter

        loadYaml gradleMainPropFile, startParameters, profiles, pluginAware
        loadYaml gradleProjectPropFile, startParameters, profiles, pluginAware
        loadYaml rootProjectPropFile, startParameters, profiles, pluginAware
        loadYaml currentProjectPropFile, startParameters, profiles, pluginAware

        resolveInlineProperties(pluginAware)
    }

    /**
     * Collects a list of profiles and a list of system/project properties, given from command-line.
     * Later on all those properties are excluded from YAML processing - so effectively they are most important ones.
     * @param gradle A Gradle object used to read StartParameter
     * @param startParameters A map used as OUT parameter, filled with all properties given from command line
     * @param profiles A map used as OUT parameter, filled with resolved profiles, those forced to be active and inactive
     */
    void collectStartParametersAndActiveProfiles(Gradle gradle, Map startParameters, Map profiles) {
        gradle.startParameter.systemPropertiesArgs.each { key, value ->
            startParameters.put(key, 'systemPropertiesArgs')
            checkProfiles(key, value, profiles)
        }

        gradle.startParameter.projectProperties.each { key, value ->
            startParameters.put(key, 'projectProperties')
            checkProfiles(key, value, profiles)
        }
    }

    /**
     * If {@code key} equals to {@code yamlProfiles} it is evaluated to get active and inactive (prefixed with !) profiles
     * @param profiles A map used as OUT parameter, filled with resolved profiles, those forced to be active and inactive
     */
    void checkProfiles(String key, String value, Map profiles) {
        if (key == 'yamlProfiles' && value) {
            // Profiles could be separated by comma or whitespace
            value.split('[\\s,]').each { _profile ->
                def profile = _profile?.trim()
                if (profile) {
                    if (profile[0] == '!') {
                        profiles.put profile[1..-1], false
                    } else {
                        profiles.put profile, true
                    }
                }
            }
        }
    }

    /**
     * Checks if file exists and is readable. If so the YAML is loaded, flatten and processed
     * @param file The file to check and load
     * @param startParameters A map containing all parameters to exclude (as they are already applied at command-line
     * @param profiles A map of active/inactive profiles
     */
    void loadYaml(File file, Map startParameters, Map profiles, pluginAware) {
        if (file && file.exists() && file.isFile() && file.canRead()) {
            file.withInputStream { input ->
                Yaml yaml = new Yaml()
                for (Object data : yaml.loadAll(input)) {
                    if (data && data instanceof Map) {
                        def dataReparsed = [:]
                        // Convert maps to proper properties, joining all with '.'
                        flattenYaml data, dataReparsed, ''
                        // Load data if any
                        if (dataReparsed) {
                            if (checkIfActive(dataReparsed, profiles)) {
                                // Process only active profiles or non-profiles
                                processParameters dataReparsed, startParameters, pluginAware
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Recurrence method used to convert maps to proper properties, joining all with '.'
     * @param source The original YAML loaded map
     * @param dest The final map with proper properties
     * @param context Recurrence parameter marking current processing context
     */
    void flattenYaml(Map source, Map dest, String context) {
        source.each { key, value ->
            if (value != null) {
                def localContext = (context ? "${context}.${key}" : key) as String
                if (value instanceof Map) {
                    flattenYaml value, dest, localContext
                } else {
                    dest.put localContext, value
                }
            }
        }
    }

    /**
     * Checks if loaded property segment is a profile or not. If it is, it check if it should be active or inactive.
     */
    boolean checkIfActive(dataReparsed, profiles) {
        // Segment that is not a profile should remain always active
        def process = true
        def profileId = dataReparsed['profile.id']
        if (profileId) {
            if (profiles.containsKey(profileId)) {
                // Command-line (de)activation is most important
                process = profiles[profileId]
            } else {
                // If there is no info at command-line, check the profile itself
                process = Boolean.parseBoolean(dataReparsed['profile.active'] as String)
            }
        }
        process
    }

    /**
     * Adds parameters in {@code dotted} and {@code camelCase} format
     * to either System properties or to project extra properties.
     * {@code project.filterTokens} are populated as well.
     * <ul>
     * <li>{@code Dotted} is {@code 'one.two.three'}</li>
     * <li>{@code Dotted} is {@code 'oneTwoThree'}</li>
     * </ul>
     *
     * See  {@link #dotToCamelCase dotToCamelCase} and {@link #camelCaseToDot camelCaseToDot}
     */
    void processParameters(Map data, Map startParameters, pluginAware) {
        data.each { key, value ->
            def system = false
            if (key.startsWith('systemProp.')) {
                system = true
                key = key['systemProp.'.length()..-1]
            }
            if (!startParameters.containsKey(key) && !key.startsWith('profile.')) {
                def camelCaseKey = dotToCamelCase(key)
                def dotKey = camelCaseToDot(key)
                pluginAware.ext[dotKey] = value
                pluginAware.ext[camelCaseKey] = value
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    def strValue = value as String
                    if (system) {
                        System.setProperty(dotKey, strValue)
                        System.setProperty(camelCaseKey, strValue)
                    } else {
                        pluginAware.ext.filterTokens[dotKey] = strValue
                        pluginAware.ext.filterTokens[camelCaseKey] = strValue
                    }
                }
            }
        }
    }

    /**
     * Adds Gradle environment properties to {@code project.filterTokens}.
     */
    void processEnvironmentProperties(pluginAware) {
        addExtraFilterTokens pluginAware, System.getenv(), 'ORG_GRADLE_PROJECT_'
    }

    /**
     * Adds Gradle system properties to {@code project.filterTokens}.
     */
    void processSystemProperties(pluginAware) {
        addExtraFilterTokens pluginAware, System.properties, 'org.gradle.project.'
    }

    /**
     * See {@link #processEnvironmentProperties(def) processEnvironmentProperties}
     * and {@link #processSystemProperties(def) processSystemProperties}
     */
    void addExtraFilterTokens(pluginAware, entries, suffix) {
        def len = suffix.length()
        entries.each { key, value ->
            def _key
            if (key.startsWith(suffix)) {
                _key = key[len..-1]
                def camelCaseKey = dotToCamelCase(_key)
                def dotKey = camelCaseToDot(_key)
                pluginAware.ext.filterTokens[dotKey] = value
                pluginAware.ext.filterTokens[camelCaseKey] = value
            }
        }
    }

    /**
     * Adds command-line project properties (-P) to {@code project.filterTokens} and project extra properties.
     * Uses {@code dotted} and {@code camelCase} format.
     */
    void processStartParameterProperties(pluginAware, startParameter) {
        /*
        def startParameterAll = [startParameter.systemPropertiesArgs, startParameter.projectProperties]
        startParameterAll.each { props ->
            props.each { key, value ->
                def camelCaseKey = dotToCamelCase(key)
                def dotKey = camelCaseToDot(key)
                pluginAware.ext.filterTokens[dotKey] = value
                pluginAware.ext.filterTokens[camelCaseKey] = value
            }
        }
        */

        startParameter.projectProperties.each { key, value ->
            def camelCaseKey = dotToCamelCase(key)
            def dotKey = camelCaseToDot(key)
            pluginAware.ext[dotKey] = value
            pluginAware.ext[camelCaseKey] = value

            pluginAware.ext.filterTokens[dotKey] = value
            pluginAware.ext.filterTokens[camelCaseKey] = value
        }
    }

    /**
     * Uses Spring's {@code PropertyResolver} to resolve values that reference to other values.
     * <ul>Example:
     * <li>one.two = '12' </li>
     * <li>one.two.three = '@one.two@.3' </li>
     * </ul>
     */
    void resolveInlineProperties(PluginAware pluginAware) {
        MutablePropertySources propertySources = new MutablePropertySources()
        propertySources.addLast(new PluginAwarePropertySource(pluginAware))
        PropertyResolver propertyResolver = new PropertySourcesPropertyResolver(propertySources)
        propertyResolver.setPlaceholderPrefix('@')
        propertyResolver.setPlaceholderSuffix('@')
        def keys = []
        def placeholder = ('@' as char) as int
        pluginAware.ext.properties.each { key, value ->
            if (key instanceof String && value instanceof String && value.indexOf(placeholder) >= 0) {
                keys.add key
            }
        }
        keys.each { key ->
            pluginAware.ext[key] = propertyResolver.getProperty(key)
            pluginAware.ext.filterTokens[key] = propertyResolver.getProperty(key)
        }
    }

    /**
     * Converts dot properties to camelCase format, so {@code 'one.two.three'} becomes {@code 'oneTwoThree'}
     */
    String dotToCamelCase(String key) {
        key.replaceAll(/\.(\w)/) { match, group -> group.toUpperCase() }
    }

    /**
     * Converts camelCase properties to dot format, so {@code 'oneTwoThree'} becomes {@code 'one.two.three'}
     */
    String camelCaseToDot(String key) {
        if (key.charAt(0).isLowerCase() && !key.contains('.')) {
            key = key.replaceAll(/([a-z0-9])([A-Z])/) { match, before, after -> before + '.' + after.toLowerCase() }
        }
        key
    }

    /**
     * Adds {@code 'requiredProperty'} and {@code 'requiredProperties'} feature to each Gradle task
     */
    void registerTaskListener(Project project) {
        project.tasks.all { task ->
            task.ext.requiredProperty = { String propertyName ->
                project.gradle.taskGraph.whenReady { graph ->
                    if (graph.hasTask(task.path)) {
                        checkProperty(project, propertyName, task)
                    }
                }
            }
            // now add the one that takes a list...
            task.ext.requiredProperties = { String[] propertyNames ->
                project.gradle.taskGraph.whenReady { graph ->
                    if (graph.hasTask(task.path)) {
                        for (propertyName in propertyNames) {
                            checkProperty(project, propertyName, task)
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if given property is available for given task.
     */
    void checkProperty(Project project, String propertyName, Task task) {
        def taskName = task.path
        if (!project.hasProperty(propertyName)) {
            throw new MissingPropertyException("You must set the '${propertyName}' property for the '$taskName' task")
        }
        // Now register the property as an input for the task.
        def propertyValue = project.property(propertyName)
        task.inputs.property(propertyName, propertyValue)
    }

    /**
     * Property source inner class for Spring's {@code PropertyResolver} feature.
     */
    static class PluginAwarePropertySource extends PropertySource<PluginAware> {

        ExtraPropertiesExtension ext;
        Map<String, String> startupProjectProperties;
        Map<String, String> startupSystemProperties;
        Map<String, String> envProperties;
        private static final String ENV_PREFIX = 'env.'

        PluginAwarePropertySource(PluginAware source) {
            super('PluginAwarePropertySource', source)
            ext = source.ext
            startupProjectProperties = source.gradle.startParameter.projectProperties
            startupSystemProperties = source.gradle.startParameter.systemPropertiesArgs
            envProperties = System.getenv()

        }

        @Override
        Object getProperty(String name) {
            if (name.startsWith(ENV_PREFIX)) {
                String envName = name[ENV_PREFIX.length()..-1]
                if (envProperties.containsKey(envName)) {
                    return envProperties.get(envName)
                }
            }
            if (startupSystemProperties.containsKey(name)) {
                return startupSystemProperties.get(name)
            }
            if (startupProjectProperties.containsKey(name)) {
                return startupProjectProperties.get(name)
            }
            return ext.get(name)
        }

        @Override
        boolean containsProperty(String name) {
            boolean has = false
            if (name.startsWith(ENV_PREFIX)) {
                String envName = name[ENV_PREFIX.length()..-1]
                has = envProperties.containsKey(envName)
            }
            return has || startupSystemProperties.containsKey(name) || startupProjectProperties.containsKey(name) || ext.has(name)
        }
    }


}
