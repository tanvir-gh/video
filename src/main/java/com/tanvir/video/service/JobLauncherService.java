package com.tanvir.video.service;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

/**
 * Creates and manages Kubernetes Jobs for stream processing. One Job per
 * stream, runs until the stream ends or backoffLimit retries are exhausted.
 *
 * The pod runs the video-detector image with --pipeline.stream and
 * STREAM_SESSION_ID/STREAM_URL env vars. It mounts the host's samples/hls
 * and work directories so it can read clips and write SQLite/state.
 */
@Service
public class JobLauncherService {

    private static final Logger log = LoggerFactory.getLogger(JobLauncherService.class);
    private static final String NAMESPACE = "default";
    private static final String IMAGE = "video-detector:latest";

    @Value("${app.k8s.kubeconfig:}")
    private String kubeconfigPath;

    @Value("${app.k8s.kafka-bootstrap:172.18.0.1:9092}")
    private String kafkaBootstrapForPod;

    @Value("${app.k8s.ollama-url:http://172.18.0.1:11434/api/chat}")
    private String ollamaUrlForPod;

    private KubernetesClient client() {
        if (kubeconfigPath != null && !kubeconfigPath.isBlank()) {
            return new KubernetesClientBuilder()
                    .withConfig(new ConfigBuilder().withFile(new File(kubeconfigPath)).build())
                    .build();
        }
        return new KubernetesClientBuilder().build();
    }

    /**
     * Launch a new stream-processing Job. Returns the sessionId.
     */
    public Map<String, Object> launch(String streamUrl) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String jobName = "stream-" + sessionId;

        try (KubernetesClient k = client()) {
            Job job = new JobBuilder()
                    .withNewMetadata()
                        .withName(jobName)
                        .withNamespace(NAMESPACE)
                        .addToLabels("app", "video-detector")
                        .addToLabels("session-id", sessionId)
                    .endMetadata()
                    .withNewSpec()
                        .withBackoffLimit(5)
                        .withActiveDeadlineSeconds(28800L)  // 8 hours
                        .withTtlSecondsAfterFinished(600)
                        .withNewTemplate()
                            .withNewMetadata()
                                .addToLabels("app", "video-detector")
                                .addToLabels("session-id", sessionId)
                            .endMetadata()
                            .withNewSpec()
                                .withRestartPolicy("Never")
                                .addNewContainer()
                                    .withName("video-detector")
                                    .withImage(IMAGE)
                                    .withImagePullPolicy("Never")
                                    .withArgs("--pipeline.stream", "--spring.main.web-application-type=none")
                                    .withEnv(List.of(
                                        new EnvVarBuilder().withName("STREAM_SESSION_ID").withValue(sessionId).build(),
                                        new EnvVarBuilder().withName("STREAM_URL").withValue(streamUrl).build(),
                                        new EnvVarBuilder().withName("APP_OLLAMA_URL").withValue(ollamaUrlForPod).build(),
                                        new EnvVarBuilder().withName("SPRING_KAFKA_BOOTSTRAP_SERVERS").withValue(kafkaBootstrapForPod).build()
                                    ))
                                    .withVolumeMounts(List.of(
                                        new VolumeMountBuilder()
                                            .withName("samples")
                                            .withMountPath("/app/samples/hls")
                                            .build(),
                                        new VolumeMountBuilder()
                                            .withName("work")
                                            .withMountPath("/app/work")
                                            .build()
                                    ))
                                    .withResources(new ResourceRequirementsBuilder()
                                        .addToRequests("memory", new Quantity("1Gi"))
                                        .addToRequests("cpu", new Quantity("500m"))
                                        .addToLimits("memory", new Quantity("4Gi"))
                                        .addToLimits("cpu", new Quantity("2"))
                                        .build())
                                .endContainer()
                                .withVolumes(List.of(
                                    new VolumeBuilder()
                                        .withName("samples")
                                        .withHostPath(new HostPathVolumeSourceBuilder()
                                            .withPath("/mnt/samples/hls")
                                            .withType("Directory")
                                            .build())
                                        .build(),
                                    new VolumeBuilder()
                                        .withName("work")
                                        .withHostPath(new HostPathVolumeSourceBuilder()
                                            .withPath("/mnt/work")
                                            .withType("DirectoryOrCreate")
                                            .build())
                                        .build()
                                ))
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                    .build();

            k.batch().v1().jobs().inNamespace(NAMESPACE).resource(job).create();
            log.info("Launched Job {} for stream {}", jobName, streamUrl);

            return Map.of(
                "sessionId", sessionId,
                "jobName", jobName,
                "streamUrl", streamUrl,
                "status", "LAUNCHED"
            );
        }
    }

    /** Stop a running stream by deleting its Job. */
    public boolean stop(String sessionId) {
        String jobName = "stream-" + sessionId;
        try (KubernetesClient k = client()) {
            var jobResource = k.batch().v1().jobs().inNamespace(NAMESPACE).withName(jobName);
            if (jobResource.get() == null) {
                return false;
            }
            jobResource.withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
            log.info("Deleted Job {}", jobName);
            return true;
        }
    }

    /** Get current status of a stream Job. */
    public Map<String, Object> status(String sessionId) {
        String jobName = "stream-" + sessionId;
        try (KubernetesClient k = client()) {
            Job job = k.batch().v1().jobs().inNamespace(NAMESPACE).withName(jobName).get();
            if (job == null) {
                return Map.of("sessionId", sessionId, "status", "NOT_FOUND");
            }
            var s = job.getStatus();
            int active = s != null && s.getActive() != null ? s.getActive() : 0;
            int succeeded = s != null && s.getSucceeded() != null ? s.getSucceeded() : 0;
            int failed = s != null && s.getFailed() != null ? s.getFailed() : 0;
            String state = succeeded > 0 ? "COMPLETE" : failed > 0 ? "FAILED" : active > 0 ? "RUNNING" : "PENDING";
            return Map.of(
                "sessionId", sessionId,
                "jobName", jobName,
                "status", state,
                "active", active,
                "succeeded", succeeded,
                "failed", failed
            );
        }
    }
}
