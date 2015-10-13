# Gradle Properties Yaml Plugin #
The Properties plugin is a plugin heavily inspired by [Gradle Properties Plugin](https://github.com/stevesaliman/gradle-properties-plugin),
[Grails 3 application.yml](http://grails.github.io/grails-doc/latest/guide/conf.html) and [Maven profiles](http://maven.apache.org/guides/introduction/introduction-to-profiles.html).

**Please note, that current implementation is more [PoC](https://en.wikipedia.org/wiki/Proof_of_concept) than production-ready code.**

See the [CHANGELOG](https://github.com/mwiktorczyk/gradle-properties-yaml-plugin/blob/master/CHANGELOG.md) for recent changes.

Gradle way of loading and resolving properties is described in [Gradle User Guide](https://docs.gradle.org/current/userguide/build_environment.html).

This plugin uses quite different concept both when it comes to property file format and order of processing. The order of execution for the properties plugin is (unlikely to Gradle original way, **the first one wins**):
        
0. The `gradle.yml` file in the current project's directory.
0. The `gradle.yml` file in the root project's directory, if the project is a module of a multi-project build.
0. The `${gradle.rootProject.name}.yml` file in Gradle user home directory (usually `~/.gradle`).
0. The `gradle.yml` file in Gradle user home directory.

Last but not least, some of the concepts are directly copied from original Gradle Properties Plugin. This is:

* A `filterTokens` thing - [link](https://github.com/stevesaliman/gradle-properties-plugin#how-do-i-use-it)
* `requiredProperty` and `requiredProperties` task extension - [link](https://github.com/stevesaliman/gradle-properties-plugin#properties-added-to-each-task)

## Custom YAML processing ##

Processing of YAML files is inspired by those in Grails 3 i.e.:

* All maps are flatten to regular properties
* Thanks to Spring's [PropertyResolver](http://docs.spring.io/spring/docs/4.2.1.RELEASE/javadoc-api/org/springframework/core/env/PropertyResolver.html) properties are evaluated just like properties in Maven
* If YAML segment is marked as profile (`profile.id`) it will be included only if it is active (`profile.active: true`) or is activated from command-line (`gradle -PyamlProfiles=fakehost`)

## Example ##

YAML file


    ---
    nexus:
      base:
        username: user1
        password: pass1
      deploy:
        username: user2
        password: pass2
    ---
    profile:
      id: localhost
      active: true
    
    hibernate.connection:
        driverClass: 'com.mysql.jdbc.Driver'
        host: localhost
        port: 3306
        url: 'jdbc:mysql://@hibernate.connection.host@:@hibernate.connection.port@/database'
    ldap:
      host: localhost
      port: 1389
      rootdn: 'cn=admin,dc=somecompany,dc=com'
      rootpw: pass3
    
    systemProp:
      q: werty
      lorem: ipsum
    
    ---
    profile:
      id: fakehost
    hibernate.connection.host: fakehost

Gradle snippet


    apply plugin: 'gradle-properties-yaml-plugin'
    
    def undotKeys(key) {
      if (!key.contains('.')) { key += 'x' }
      key.replaceAll(/\.(\w)/) { match, group -> group.toUpperCase() }
    }
    
    task printProps << {
      project.ext.properties
        .sort { e1, e2 -> undotKeys(e1.key) <=> undotKeys(e2.key) }
        .each { k, v -> if (k != 'filterTokens') println "${k} :: ${v}" }
      println '\nChecking system properties\n'
      println System.getProperty('lorem', /'lorem'' does not exists as system property/)
      println System.getProperty('ldap.rootpw', /'ldap.rootpw' does not exists as system property/)
    }
    
Gradle output (`gradle printProps`)


    hibernate.connection.driverClass :: com.mysql.jdbc.Driver
    hibernateConnectionDriverClass :: com.mysql.jdbc.Driver
    hibernate.connection.host :: localhost
    hibernateConnectionHost :: localhost
    hibernate.connection.port :: 3306
    hibernateConnectionPort :: 3306
    hibernate.connection.url :: jdbc:mysql://localhost:3306/database
    hibernateConnectionUrl :: jdbc:mysql://localhost:3306/database
    ldap.host :: localhost
    ldapHost :: localhost
    ldap.port :: 1389
    ldapPort :: 1389
    ldap.rootdn :: cn=admin,dc=somecompany,dc=com
    ldapRootdn :: cn=admin,dc=somecompany,dc=com
    ldap.rootpw :: pass3
    ldapRootpw :: pass3
    lorem :: ipsum
    nexus.base.password :: pass1
    nexusBasePassword :: pass1
    nexus.base.username :: user1
    nexusBaseUsername :: user1
    nexus.deploy.password :: pass2
    nexusDeployPassword :: pass2
    nexus.deploy.username :: user2
    nexusDeployUsername :: user2
    org.gradle.daemon :: true
    
    Checking system properties
    
    ipsum
    'ldap.rootpw' does not exists as system property


Gradle output (`gradle printProps -PyamlProfiles=fakehost`)

    hibernate.connection.driverClass :: com.mysql.jdbc.Driver
    hibernateConnectionDriverClass :: com.mysql.jdbc.Driver
    hibernate.connection.host :: fakehost
    hibernateConnectionHost :: fakehost
    hibernate.connection.port :: 3306
    hibernateConnectionPort :: 3306
    hibernate.connection.url :: jdbc:mysql://fakehost:3306/database
    hibernateConnectionUrl :: jdbc:mysql://fakehost:3306/database
    ldap.host :: localhost
    ldapHost :: localhost
    ldap.port :: 1389
    ldapPort :: 1389
    ldap.rootdn :: cn=admin,dc=somecompany,dc=com
    ldapRootdn :: cn=admin,dc=somecompany,dc=com
    ldap.rootpw :: pass3
    ldapRootpw :: pass3
    lorem :: ipsum
    nexus.base.password :: pass1
    nexusBasePassword :: pass1
    nexus.base.username :: user1
    nexusBaseUsername :: user1
    nexus.deploy.password :: pass2
    nexusDeployPassword :: pass2
    nexus.deploy.username :: user2
    nexusDeployUsername :: user2
    org.gradle.daemon :: true
    yaml.profiles :: fakehost
    yamlProfiles :: fakehost
    
    Checking system properties
    
    ipsum
    'ldap.rootpw' does not exists as system property


Gradle output (`gradle printProps -PyamlProfiles=fakehost,!localhost`)

    hibernate.connection.host :: fakehost
    hibernateConnectionHost :: fakehost
    nexus.base.password :: pass1
    nexusBasePassword :: pass1
    nexus.base.username :: user1
    nexusBaseUsername :: user1
    nexus.deploy.password :: pass2
    nexusDeployPassword :: pass2
    nexus.deploy.username :: user2
    nexusDeployUsername :: user2
    org.gradle.daemon :: true
    yaml.profiles :: fakehost,!localhost
    yamlProfiles :: fakehost,!localhost
    
    Checking system properties
    
    'lorem'' does not exists as system property
    'ldap.rootpw' does not exists as system property


## Usage ##

To use the plugin with Gradle, add the following to `build.gradle`:

    // Pull the plugin from Bintray
    buildscript {
      repositories {
        jcenter()
        maven {
            url  "http://dl.bintray.com/mariusz/maven"
        }
    }
      dependencies {
        classpath 'pl.softmate:gradle-properties-yaml-plugin:0.0.1'
      }
    }
    
    // invoke the plugin
    apply plugin: 'gradle-properties-yaml-plugin'