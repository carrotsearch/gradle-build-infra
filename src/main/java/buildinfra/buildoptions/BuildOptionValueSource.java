package buildinfra.buildoptions;

public enum BuildOptionValueSource {
  GRADLE_PROPERTY,
  SYSTEM_PROPERTY,
  ENVIRONMENT_VARIABLE,
  EXPLICIT_VALUE,
  COMPUTED_VALUE,
  LOCAL_OPTIONS_FILE
}
