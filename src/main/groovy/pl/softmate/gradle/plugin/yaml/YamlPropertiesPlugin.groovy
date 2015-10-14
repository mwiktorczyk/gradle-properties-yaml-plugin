package pl.softmate.gradle.plugin.yaml

import org.gradle.api.Plugin
import org.gradle.api.Project
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
 * Created by mwiktorczyk on 11.10.15.
 */
class YamlPropertiesPlugin implements Plugin<PluginAware> {

    @Override
    void apply(PluginAware pluginAware) {
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

        def startParameters = [:]
        def profiles = [:]

        gradle.startParameter.systemPropertiesArgs.each { key, value ->
            startParameters.put(key, 'systemPropertiesArgs')
            checkProfiles(key, value, profiles)
        }

        gradle.startParameter.projectProperties.each { key, value ->
            startParameters.put(key, 'projectProperties')
            checkProfiles(key, value, profiles)
        }

        pluginAware.ext.filterTokens = [:]

        def gradleMainPropFile = Paths.get(gradle.gradleUserHomeDir.absolutePath, 'gradle.yml').toFile()
        def gradleProjectPropFile = Paths.get(gradle.gradleUserHomeDir.absolutePath, "${gradle.rootProject.name}.yml").toFile()
        def rootProjectPropFile = Paths.get(gradle.rootProject.projectDir.absolutePath, 'gradle.yml').toFile()
        def currentProjectPropFile = null
        if (currentProject && gradle.rootProject.projectDir != currentProject.projectDir) {
            currentProjectPropFile = Paths.get(currentProject.projectDir.absolutePath, 'gradle.yml').toFile()
        }

        processEnvironmentProperties pluginAware
        processSystemProperties pluginAware
        processStartParameterProperties pluginAware, gradle.startParameter

        loadYaml gradleMainPropFile, startParameters, profiles, pluginAware
        loadYaml gradleProjectPropFile, startParameters, profiles, pluginAware
        loadYaml rootProjectPropFile, startParameters, profiles, pluginAware
        loadYaml currentProjectPropFile, startParameters, profiles, pluginAware

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

    def checkProfiles(String key, String value, Map profiles) {
        if (key == 'yamlProfiles' && value) {
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

    def loadYaml(File file, Map startParameters, Map profiles, pluginAware) {
        if (file && file.exists() && file.isFile() && file.canRead()) {
            file.withInputStream { input ->
                Yaml yaml = new Yaml()
                for (Object data : yaml.loadAll(input)) {
                    if (data && data instanceof Map) {
                        def dataReparsed = [:]
                        flattenYaml data, dataReparsed, ''
                        if (dataReparsed) {
                            if (checkIfActive(dataReparsed, profiles)) {
                                processParameters dataReparsed, startParameters, pluginAware
                            }
                        }
                    }
                }
            }
        }
    }

    def flattenYaml(Map source, Map dest, String context) {
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

    def checkIfActive(Map dataReparsed, Map profiles) {
        def process = true
        def profileId = dataReparsed['profile.id']
        if (profileId) {
            if (profiles.containsKey(profileId)) {
                process = profiles[profileId]
            } else {
                process = Boolean.parseBoolean(dataReparsed['profile.active'] as String)
            }
        }
        process
    }

    def processParameters(Map data, Map startParameters, pluginAware) {
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

    def processEnvironmentProperties(pluginAware) {
        addExtraFilterTokens pluginAware, System.getenv(), 'ORG_GRADLE_PROJECT_'
    }

    def processSystemProperties(pluginAware) {
        addExtraFilterTokens pluginAware, System.properties, 'org.gradle.project.'
    }

    def addExtraFilterTokens(pluginAware, entries, suffix) {
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

    def processStartParameterProperties(pluginAware, startParameter) {
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

    String dotToCamelCase(String key) {
        key.replaceAll(/\.(\w)/) { match, group -> group.toUpperCase() }
    }

    String camelCaseToDot(String key) {
        if (key.charAt(0).isLowerCase() && !key.contains('.')) {
            key = key.replaceAll(/([a-z0-9])([A-Z])/) { match, before, after -> before + '.' + after.toLowerCase() }
        }
        key
    }

    def registerTaskListener(project) {
        project.tasks.all { task ->
            task.ext.requiredProperty = { String propertyName ->
                project.gradle.taskGraph.whenReady { graph ->
                    if (graph.hasTask(task.path)) {
                        checkProperty(project, propertyName, task, "requiredProperty")
                    }
                }
            }
            // now add the one that takes a list...
            task.ext.requiredProperties = { String[] propertyNames ->
                project.gradle.taskGraph.whenReady { graph ->
                    if (graph.hasTask(task.path)) {
                        for (propertyName in propertyNames) {
                            checkProperty(project, propertyName, task, "requiredProperties")
                        }
                    }
                }
            }
        }
    }

    def checkProperty(project, propertyName, task, caller) {
        def taskName = task.path
        if (!project.hasProperty(propertyName)) {
            throw new MissingPropertyException("You must set the '${propertyName}' property for the '$taskName' task")
        }
        // Now register the property as an input for the task.
        def propertyValue = project.property(propertyName)
        task.inputs.property(propertyName, propertyValue)
    }

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
