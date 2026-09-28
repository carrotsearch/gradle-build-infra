package com.carrotsearch.gradle.buildinfra;

import java.util.LinkedHashMap;
import java.util.Map;

/** Build options that were renamed; the old names fail the build when still used. */
public final class DeprecatedBuildOptions {
  /** Deprecated option name to its replacement. */
  public static final Map<String, String> RENAMES;

  static {
    var renames = new LinkedHashMap<String, String>();
    renames.put("task.times", "buildinfra.taskTimes");
    renames.put("task.times.aggregate", "buildinfra.taskTimes.aggregate");
    renames.put("task.times.limit", "buildinfra.taskTimes.limit");
    renames.put("task.times.mintime.millis", "buildinfra.taskTimes.minTimeMillis");
    renames.put("task.times.time.format", "buildinfra.taskTimes.timeFormat");
    renames.put("tests.slowestTests", "buildinfra.slowestTests");
    renames.put("tests.slowestTests.minTime", "buildinfra.slowestTests.minTime");
    renames.put("tests.slowestSuites", "buildinfra.slowestTests.suites");
    renames.put("tests.slowestSuites.minTime", "buildinfra.slowestTests.suites.minTime");
    renames.put("buildinfra.spotlessGradleGroovyScripts", "buildinfra.spotless.gradleScripts");
    renames.put("forbiddenApisDir", "buildinfra.forbiddenApis.dir");
    renames.put("check.gradlewrapper.consistency", "buildinfra.gradleWrapper.consistency");
    RENAMES =
        Map.copyOf(renames).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .collect(
                LinkedHashMap::new, (map, e) -> map.put(e.getKey(), e.getValue()), Map::putAll);
  }

  private DeprecatedBuildOptions() {}
}
