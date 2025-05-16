package com.carrotsearch.gradle.dependencychecks.it

import org.gradle.testkit.runner.TaskOutcome

class BuildOptionsPluginSpec extends AbstractIntegTest {
    def "provides buildOptions extension and configures basic options"() {
        given:
        buildFile(
                """
        plugins {
          id('com.carrotsearch.gradle.buildinfra') apply false
        }
        apply plugin: com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin

        Provider<String> stringOption = buildOptions.addOption("a01", "a01 description", "default-value-a01")
        Provider<Boolean> boolOption  = buildOptions.addBooleanOption("a02", "a02 description", false)

        buildOptions {
          addOption("a99", "a99 description")
        }
        
        tasks.register("printOptions", {
          doLast {
            logger.lifecycle("a01: " + buildOptions['a01'].get())
            logger.lifecycle("a02: " + buildOptions['a02'].get())
            logger.lifecycle("a99: " + buildOptions['a99'].isPresent())
          }
        })
        """)

        when:
        def result = gradleRunner()
                .withArguments("printOptions")
                .run()

        then:
        containsLines(result.output, """
          a01: default-value-a01
          a02: false
          a99: false
        """)
        result.task(":printOptions").outcome == TaskOutcome.SUCCESS
    }

    def "provides buildOptions task that shows all options, their sources and values"() {
        given:
        buildFile(
        """
        plugins {
          id('com.carrotsearch.gradle.buildinfra') apply false
        }
        apply plugin: com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin

        def stringValueProv = project.providers.provider { 'default-value-a03' }
        def boolValueProv = project.providers.provider { false }
        def intValueProv = project.providers.provider { 14 }
        buildOptions {
            addOption("a01", "a01 description", "default-value-a01")
            addOption("a02", "a02 description")
            addOption("a03", "a03 description", stringValueProv)
            addBooleanOption("a04", "a04 description", true)
            addBooleanOption("a05", "a05 description")
            addBooleanOption("a06", "a06 description", boolValueProv)
            addIntOption("a07", "a07 description", 13)
            addIntOption("a08", "a08 description")
            addIntOption("a09", "a09 description", intValueProv)
        }
        """)

        when:
        def result = gradleRunner()
                .withArguments("buildOptions")
                .run()

        then:
        containsLines(result.output, """
        a01 = default-value-a01 # a01 description
        a02 = [empty]  # a02 description
        a03 = default-value-a03 # (source: computed value) a03 description
        a04 = true # (type: boolean) a04 description
        a05 = [empty]  # (type: boolean) a05 description
        a06 = false # (type: boolean, source: computed value) a06 description
        a07 = 13 # (type: integer) a07 description
        a08 = [empty]  # (type: integer) a08 description
        a09 = 14 # (type: integer, source: computed value) a09 description
        """)
        result.task(":buildOptions").outcome == TaskOutcome.SUCCESS
    }
}