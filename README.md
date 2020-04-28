# <img align="left" src="images/image-grinder.png">Cache Horizon:<br>Gradle build cache for a group of tasks

<!---freshmark shields
output = [
    link(shield('Gradle plugin', 'plugins.gradle.org', 'com.diffplug.cache-horizon', 'blue'), 'https://plugins.gradle.org/plugin/com.diffplug.cache-horizon'),
    link(shield('Maven central', 'mavencentral', 'available', 'blue'), 'https://search.maven.org/artifact/com.diffplug/cache-horizon'),
    link(shield('Apache 2.0', 'license', 'apache-2.0', 'blue'), 'https://tldrlegal.com/license/apache-license-2.0-(apache-2.0)'),
    '',
    link(shield('Changelog', 'changelog', versionLast, 'brightgreen'), 'CHANGELOG.md'),
    link(shield('Javadoc', 'javadoc', 'yes', 'brightgreen'), 'https://javadoc.io/doc/com.diffplug/cache-horizon/{{versionLast}}/index.html'),
    link(shield('Live chat', 'gitter', 'chat', 'brightgreen'), 'https://gitter.im/diffplug/cache-horizon'),
    link(image('JitCI', 'https://jitci.com/gh/diffplug/cache-horizon/svg'), 'https://jitci.com/gh/diffplug/cache-horizon')
    ].join('\n');
-->
[![Gradle plugin](https://img.shields.io/badge/plugins.gradle.org-com.diffplug.cache--horizon-blue.svg)](https://plugins.gradle.org/plugin/com.diffplug.cache-horizon)
[![Maven central](https://img.shields.io/badge/mavencentral-available-blue.svg)](https://search.maven.org/artifact/com.diffplug/cache-horizon)
[![Apache 2.0](https://img.shields.io/badge/license-apache--2.0-blue.svg)](https://tldrlegal.com/license/apache-license-2.0-(apache-2.0))

[![Changelog](https://img.shields.io/badge/changelog-first--ever-brightgreen.svg)](CHANGELOG.md)
[![Javadoc](https://img.shields.io/badge/javadoc-yes-brightgreen.svg)](https://javadoc.io/doc/com.diffplug/cache-horizon/first-ever/index.html)
[![Live chat](https://img.shields.io/badge/gitter-chat-brightgreen.svg)](https://gitter.im/diffplug/cache-horizon)
[![JitCI](https://jitci.com/gh/diffplug/cache-horizon/svg)](https://jitci.com/gh/diffplug/cache-horizon)
<!---freshmark /shields -->

<!---freshmark javadoc
output = prefixDelimiterReplace(input, 'https://javadoc.io/static/com.diffplug.gradle/image-grinder/', '/', versionLast);
-->

## Cache a *group* of tasks

Take the following task dependency trees:

```
[install node] -> [npm install] -> [npm run complexJsBuild]
[start docker] -> [run command in docker] -> [stop docker]
```

The important things about this are:

- the **group** of tasks can easily be defined as `f(inputs) = output_files`
- individually, they cannot be defined that way, because some steps modify the environment (rather than only generating output files)
- if you could cache the **group**, then you could avoid not just executing a task, but installing and configuring an entire toolchain
- task-level caching doesn't work - you can't cache "bringing up docker", even though you can easily cache `f(dockerfile, input_files) -> outputs`

## How it works

```gradle
plugins {
    id 'com.diffplug.cache-horizon'
}

cacheHorizon {
    add 'nodeInstall', 'npmInstall', 'complexJsBuild'
    inputsAndOutputs {
        inputs.property('nodeVersion', nodeTask.nodeVersion)
        inputs.file('package-lock.json').withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir('src/main/typescript').withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file('build/index.js')
    }
}
```

This will add two tasks with the following dependency arrangement

```
                        +------------------------------+
cacheHorizonIsCached -> | nodeInstall, npmInstall, ... | -> cacheHorizon
                        +------------------------------+
```

When `cacheHorizonIsCached` runs, it checks to see if `cacheHorizon` is able to be restored from cache.  If it is, then it disables `nodeInstall`, `npmInstall`, etc.  When `cacheHorizon` eventually runs, it will just restore `index.js` from cache, avoiding all the intermediate work.

## Multiple horizons

Usually there's probably just one `cacheHorizon` per project.  But if you want more, you can do

```gradle
cacheHorizon {
    named 'dockerHorizon', {
        add 'dockerStart', 'dockerRun', 'dockerStop'
        inputsAndOutputs { ... }
    }
```

Now you'll have `dockerHorizon` and `dockerHorizonIsCached` tasks in your tree.

## Limitations

It doesn't work, but we think it could.  See [here](TODO) for its current status and to help if you can.

It will definitely require using Gradle's internal API, so there will be compatibility delays (e.g. when Gradle N comes out, it might be a little while until `cache-horizon` has been tested and fixed for that version).

<!---freshmark /javadoc -->

## Acknowledgements

* Maintained by [DiffPlug](https://www.diffplug.com/).
