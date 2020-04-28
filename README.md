# Cache Horizon:<br>Use gradle build cache for a group of tasks

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

## The problem

Take the following task trees:

```
[install node] -> [npm install] -+-> [npm run compileSass      ] + -> [npm run concatMinify]
                                 +-> [npm run compileTypescript] +

[start docker] -> [run command in docker] -> [stop docker]
```

- the **group** of tasks can easily be defined as `f(inputs) = output_files`
- but individually they can't, because some steps modify the environment
    - you can't cache `start docker` ...
- if you could somehow cache the **group**, then you could avoid not just a task, but an entire toolchain
    - ...but you can easily cache `f(dockerfile, input_files) -> output_files`

## A solution

```gradle
plugins {
    id 'com.diffplug.cache-horizon'
}

cacheHorizon {
    add 'nodeInstall', 'npmInstall', 'compileSass', 'compileTypescript'
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

When `cacheHorizonIsCached` executes, it looks forward to check if `cacheHorizon` is able to be restored from cache.  If it is, then it disables `nodeInstall`, `npmInstall`, etc.  When `cacheHorizon` eventually runs, it will just restore `index.js` from cache, avoiding all the intermediate work.

**DANGER** no outside task should depend on anything within the horizon (e.g. `test` dependsOn `compileTypescript` is bad, it should instead depend on `cacheHorizon`).  It is fine if tasks within the horizon depend on anything outside the horizon (e.g. `compileTypescript` dependsOn `lintTypescript` is fine).

## Multiple cache horizons in one project

Usually you only need one `cacheHorizon` per project.  But if you want more, you can do this:

```gradle
cacheHorizon {
    named 'dockerHorizon', {
        add 'dockerStart', 'dockerRun', 'dockerStop'
        inputsAndOutputs { ... }
    }
```

Now you'll have `dockerHorizon` and `dockerHorizonIsCached` tasks.

## Limitations 🔴This project has not been implemented🔴

We started to implement it, got stuck, and found an easier way around.  We're publishing what we did in case it helps you get to the finish line.  See [this issue](TODO) for the latest on our progress.  If somebody builds something else which solves this problem we'll link out from there.

Cache horizon uses Gradle's internal API, so there will be compatibility delays (e.g. when Gradle N comes out, it might be a little while until `cache-horizon` has been tested and fixed for the new version).

<!---freshmark /javadoc -->

## Acknowledgements

* Maintained by [DiffPlug](https://www.diffplug.com/).
