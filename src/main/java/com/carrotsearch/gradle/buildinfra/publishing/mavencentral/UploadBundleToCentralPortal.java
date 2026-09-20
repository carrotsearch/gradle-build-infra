package com.carrotsearch.gradle.buildinfra.publishing.mavencentral;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Uploads a bundle of Maven artifacts using Central Portal's publisher API and, optionally, waits
 * until the deployment is validated.
 *
 * @see "https://central.sonatype.org/publish/publish-portal-api/"
 */
@UntrackedTask(because = "Uploads to an external service")
public abstract class UploadBundleToCentralPortal extends DefaultTask {
  private static final Pattern DEPLOYMENT_STATE =
      Pattern.compile("\"deploymentState\"\\s*:\\s*\"(?<state>[A-Z_]+)\"");

  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getBundleFile();

  @Internal
  public abstract Property<String> getDeploymentName();

  @Internal
  public abstract Property<String> getPublishingType();

  @Internal
  public abstract Property<String> getPortalUrl();

  @Internal
  public abstract Property<String> getUsername();

  @Internal
  public abstract Property<String> getPassword();

  @Internal
  public abstract Property<Duration> getValidationTimeout();

  @Internal
  public abstract Property<Duration> getStatusPollInterval();

  @Internal
  public abstract Property<Boolean> getSnapshotVersion();

  @TaskAction
  public void upload() throws IOException {
    if (getSnapshotVersion().getOrElse(false)) {
      throw new GradleException(
          "Snapshot versions can't be uploaded to Central Portal as a bundle.");
    }

    if (!getUsername().isPresent() || !getPassword().isPresent()) {
      throw new GradleException(
          "Central Portal credentials are required: set 'mavenCentralUsername' and"
              + " 'mavenCentralPassword' gradle properties (or the corresponding"
              + " ORG_GRADLE_PROJECT_* environment variables) to the name and password of"
              + " a Central Portal user token.");
    }

    var publishingType = PublishingType.parse(getPublishingType().get());
    var authorization =
        "Bearer "
            + Base64.getEncoder()
                .encodeToString(
                    (getUsername().get() + ":" + getPassword().get())
                        .getBytes(StandardCharsets.UTF_8));
    var apiUrl = getPortalUrl().get().replaceAll("/+$", "") + "/api/v1/publisher";

    try (HttpClient client =
        HttpClient.newBuilder()
            // Avoid h2/h2c upgrade problems, there's nothing to gain from HTTP/2 here.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build()) {
      String deploymentId = uploadBundle(client, apiUrl, authorization, publishingType);
      getLogger().lifecycle("Deployment ID: {}", deploymentId);

      Duration timeout = getValidationTimeout().get();
      if (timeout.isZero() || timeout.isNegative()) {
        getLogger().lifecycle("Not waiting for the deployment to be validated.");
      } else {
        awaitValidated(client, apiUrl, authorization, publishingType, deploymentId, timeout);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GradleException("Interrupted while talking to Central Portal.", e);
    }
  }

  private String uploadBundle(
      HttpClient client, String apiUrl, String authorization, PublishingType publishingType)
      throws IOException, InterruptedException {
    Path bundle = getBundleFile().get().getAsFile().toPath();
    getLogger()
        .lifecycle(
            "Uploading bundle: {} ({} bytes, {})", bundle, Files.size(bundle), publishingType);

    String boundary = "----bundle-" + UUID.randomUUID().toString().replace("-", "");
    String partHeader =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"bundle\"; filename=\""
            + bundle.getFileName()
            + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n";
    String partFooter = "\r\n--" + boundary + "--\r\n";

    var uri =
        URI.create(
            apiUrl
                + "/upload?publishingType="
                + publishingType.name()
                + "&name="
                + URLEncoder.encode(getDeploymentName().get(), StandardCharsets.UTF_8));

    // All the publishers have a known length so the request has a content-length header
    // (no chunked encoding), while the bundle is still streamed from disk.
    var request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", authorization)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Accept", "application/json, text/plain")
            .timeout(Duration.ofHours(1))
            .POST(
                BodyPublishers.concat(
                    BodyPublishers.ofByteArray(partHeader.getBytes(StandardCharsets.UTF_8)),
                    BodyPublishers.ofFile(bundle),
                    BodyPublishers.ofByteArray(partFooter.getBytes(StandardCharsets.UTF_8))))
            .build();

    HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 201) {
      throw new GradleException(
          String.format(
              Locale.ROOT,
              "Bundle upload failed, Central Portal responded with HTTP %d: %s",
              response.statusCode(),
              response.body()));
    }
    return response.body().trim();
  }

  private void awaitValidated(
      HttpClient client,
      String apiUrl,
      String authorization,
      PublishingType publishingType,
      String deploymentId,
      Duration timeout)
      throws InterruptedException {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    apiUrl
                        + "/status?id="
                        + URLEncoder.encode(deploymentId, StandardCharsets.UTF_8)))
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .timeout(Duration.ofMinutes(1))
            .POST(BodyPublishers.noBody())
            .build();

    long deadline = System.nanoTime() + timeout.toNanos();
    String lastState = null;
    while (true) {
      String state = null;
      try {
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          Matcher matcher = DEPLOYMENT_STATE.matcher(response.body());
          state = matcher.find() ? matcher.group("state") : "UNKNOWN";
          if (!state.equals(lastState)) {
            getLogger().lifecycle("Deployment state: {}", state);
            lastState = state;
          }

          switch (state) {
            case "FAILED" ->
                throw new GradleException(
                    "Deployment " + deploymentId + " failed validation: " + response.body());
            case "VALIDATED" -> {
              if (publishingType == PublishingType.USER_MANAGED) {
                getLogger()
                    .lifecycle(
                        "Deployment validated, publish (or drop) it at: {}/publishing/deployments",
                        getPortalUrl().get());
                return;
              }
            }
            case "PUBLISHING", "PUBLISHED" -> {
              return;
            }
            default -> {
              // Keep waiting.
            }
          }
        } else {
          getLogger()
              .warn(
                  "Deployment status check returned HTTP {}: {}",
                  response.statusCode(),
                  response.body());
        }
      } catch (IOException e) {
        getLogger().warn("Deployment status check failed, will retry: {}", e.toString());
      }

      if (System.nanoTime() - deadline >= 0) {
        throw new GradleException(
            String.format(
                Locale.ROOT,
                "Timed out waiting for deployment %s to be validated (last state: %s)."
                    + " The deployment may still complete, check: %s/publishing/deployments",
                deploymentId,
                state,
                getPortalUrl().get()));
      }
      Thread.sleep(getStatusPollInterval().get().toMillis());
    }
  }
}
