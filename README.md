# jfix-armeria

Provides useful integrations of jfix components with [Armeria](https://github.com/line/armeria) library

## jfix-armeria-facade

Entry-point module to all features, provided by jfix-armeria library. 

The module provides several Gradle capabilities to do not force client libraries to depend on JFix components they don't need. List of provided capabilities is following:
*  _jfix-armeria-facade-dynamic-request-support_
*  _jfix-armeria-facade-aggregating-profiler-support_
*  _jfix-armeria-facade-rate-limiter-support_
*  _jfix-armeria-facade-retrofit-support_
*  _jfix-armeria-facade-jfix-stdlib-executors-support_

Since Maven doesn't support Gradle capabilities, for Maven project convenience jfix-armeria provides _jfix-armeria-facade-all_ and _jfix-armeria-facade-all-retrofit_ modules with all required dependencies (JFix components and [Retrofit](https://armeria.dev/docs/client-retrofit) for the 2nd module) connected.