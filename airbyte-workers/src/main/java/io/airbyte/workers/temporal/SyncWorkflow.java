/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.workers.temporal;

import com.google.common.annotations.VisibleForTesting;
import io.airbyte.commons.functional.CheckedSupplier;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.AirbyteConfigsValidator;
import io.airbyte.config.ConfigSchema;
import io.airbyte.config.NormalizationInput;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.StandardSyncOutput;
import io.airbyte.scheduler.models.IntegrationLauncherConfig;
import io.airbyte.scheduler.models.JobRunConfig;
import io.airbyte.workers.DefaultNormalizationWorker;
import io.airbyte.workers.DefaultReplicationWorker;
import io.airbyte.workers.Worker;
import io.airbyte.workers.WorkerConstants;
import io.airbyte.workers.normalization.NormalizationRunnerFactory;
import io.airbyte.workers.process.AirbyteIntegrationLauncher;
import io.airbyte.workers.process.IntegrationLauncher;
import io.airbyte.workers.process.ProcessBuilderFactory;
import io.airbyte.workers.protocols.airbyte.AirbyteMessageTracker;
import io.airbyte.workers.protocols.airbyte.AirbyteSource;
import io.airbyte.workers.protocols.airbyte.DefaultAirbyteDestination;
import io.airbyte.workers.protocols.airbyte.DefaultAirbyteSource;
import io.airbyte.workers.protocols.airbyte.EmptyAirbyteSource;
import io.airbyte.workers.protocols.airbyte.NamespacingMapper;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WorkflowInterface
public interface SyncWorkflow {

  @WorkflowMethod
  StandardSyncOutput run(JobRunConfig jobRunConfig,
                         IntegrationLauncherConfig sourceLauncherConfig,
                         IntegrationLauncherConfig destinationLauncherConfig,
                         StandardSyncInput syncInput);

  class WorkflowImpl implements SyncWorkflow {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowImpl.class);

    private final ActivityOptions options = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofDays(3))
        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
        .setRetryOptions(TemporalUtils.NO_RETRY)
        .build();

    private final ReplicationActivity replicationActivity = Workflow.newActivityStub(ReplicationActivity.class, options);
    private final NormalizationActivity normalizationActivity = Workflow.newActivityStub(NormalizationActivity.class, options);

    @Override
    public StandardSyncOutput run(JobRunConfig jobRunConfig,
                                  IntegrationLauncherConfig sourceLauncherConfig,
                                  IntegrationLauncherConfig destinationLauncherConfig,
                                  StandardSyncInput syncInput) {
      final StandardSyncOutput run = replicationActivity.replicate(jobRunConfig, sourceLauncherConfig, destinationLauncherConfig, syncInput);

      final NormalizationInput normalizationInput = new NormalizationInput()
          .withDestinationConfiguration(syncInput.getDestinationConfiguration())
          .withCatalog(run.getOutputCatalog());

      normalizationActivity.normalize(jobRunConfig, destinationLauncherConfig, normalizationInput);

      return run;
    }

  }

  @ActivityInterface
  interface ReplicationActivity {

    @ActivityMethod
    StandardSyncOutput replicate(JobRunConfig jobRunConfig,
                                 IntegrationLauncherConfig sourceLauncherConfig,
                                 IntegrationLauncherConfig destinationLauncherConfig,
                                 StandardSyncInput syncInput);

  }

  class ReplicationActivityImpl implements ReplicationActivity {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicationActivityImpl.class);

    private final ProcessBuilderFactory pbf;
    private final Path workspaceRoot;
    private final AirbyteConfigsValidator validator;

    public ReplicationActivityImpl(ProcessBuilderFactory pbf, Path workspaceRoot) {
      this(pbf, workspaceRoot, new AirbyteConfigsValidator());
    }

    @VisibleForTesting
    ReplicationActivityImpl(ProcessBuilderFactory pbf, Path workspaceRoot, AirbyteConfigsValidator validator) {
      this.pbf = pbf;
      this.workspaceRoot = workspaceRoot;
      this.validator = validator;
    }

    public StandardSyncOutput replicate(JobRunConfig jobRunConfig,
                                        IntegrationLauncherConfig sourceLauncherConfig,
                                        IntegrationLauncherConfig destinationLauncherConfig,
                                        StandardSyncInput syncInput) {

      final Supplier<StandardSyncInput> inputSupplier = () -> {
        validator.ensureAsRuntime(ConfigSchema.STANDARD_SYNC_INPUT, Jsons.jsonNode(syncInput));
        return syncInput;
      };

      final TemporalAttemptExecution<StandardSyncInput, StandardSyncOutput> temporalAttemptExecution = new TemporalAttemptExecution<>(
          workspaceRoot,
          jobRunConfig,
          getWorkerFactory(sourceLauncherConfig, destinationLauncherConfig, jobRunConfig, syncInput),
          inputSupplier,
          new CancellationHandler.TemporalCancellationHandler());

      return temporalAttemptExecution.get();
    }

    private CheckedSupplier<Worker<StandardSyncInput, StandardSyncOutput>, Exception> getWorkerFactory(
                                                                                                       IntegrationLauncherConfig sourceLauncherConfig,
                                                                                                       IntegrationLauncherConfig destinationLauncherConfig,
                                                                                                       JobRunConfig jobRunConfig,
                                                                                                       StandardSyncInput syncInput) {
      return () -> {
        final IntegrationLauncher sourceLauncher = new AirbyteIntegrationLauncher(
            sourceLauncherConfig.getJobId(),
            Math.toIntExact(sourceLauncherConfig.getAttemptId()),
            sourceLauncherConfig.getDockerImage(),
            pbf);
        final IntegrationLauncher destinationLauncher = new AirbyteIntegrationLauncher(
            destinationLauncherConfig.getJobId(),
            Math.toIntExact(destinationLauncherConfig.getAttemptId()),
            destinationLauncherConfig.getDockerImage(),
            pbf);

        // reset jobs use an empty source to induce resetting all data in destination.
        final AirbyteSource airbyteSource =
            sourceLauncherConfig.getDockerImage().equals(WorkerConstants.RESET_JOB_SOURCE_DOCKER_IMAGE_STUB) ? new EmptyAirbyteSource()
                : new DefaultAirbyteSource(sourceLauncher);

        return new DefaultReplicationWorker(
            jobRunConfig.getJobId(),
            Math.toIntExact(jobRunConfig.getAttemptId()),
            airbyteSource,
            new NamespacingMapper(syncInput.getPrefix()),
            new DefaultAirbyteDestination(destinationLauncher),
            new AirbyteMessageTracker());
      };
    }

  }

  @ActivityInterface
  interface NormalizationActivity {

    @ActivityMethod
    Void normalize(JobRunConfig jobRunConfig,
                   IntegrationLauncherConfig destinationLauncherConfig,
                   NormalizationInput input);

  }

  class NormalizationActivityImpl implements NormalizationActivity {

    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizationActivityImpl.class);

    private final ProcessBuilderFactory pbf;
    private final Path workspaceRoot;
    private final AirbyteConfigsValidator validator;

    public NormalizationActivityImpl(ProcessBuilderFactory pbf, Path workspaceRoot) {
      this(pbf, workspaceRoot, new AirbyteConfigsValidator());
    }

    @VisibleForTesting
    NormalizationActivityImpl(ProcessBuilderFactory pbf, Path workspaceRoot, AirbyteConfigsValidator validator) {
      this.pbf = pbf;
      this.workspaceRoot = workspaceRoot;
      this.validator = validator;
    }

    public Void normalize(JobRunConfig jobRunConfig,
                          IntegrationLauncherConfig destinationLauncherConfig,
                          NormalizationInput input) {

      final Supplier<NormalizationInput> inputSupplier = () -> {
        validator.ensureAsRuntime(ConfigSchema.NORMALIZATION_INPUT, Jsons.jsonNode(input));
        return input;
      };

      final TemporalAttemptExecution<NormalizationInput, Void> temporalAttemptExecution = new TemporalAttemptExecution<>(
          workspaceRoot,
          jobRunConfig,
          getWorkerFactory(destinationLauncherConfig, jobRunConfig, input),
          inputSupplier,
          new CancellationHandler.TemporalCancellationHandler());

      return temporalAttemptExecution.get();
    }

    private CheckedSupplier<Worker<NormalizationInput, Void>, Exception> getWorkerFactory(IntegrationLauncherConfig destinationLauncherConfig,
                                                                                          JobRunConfig jobRunConfig,
                                                                                          NormalizationInput normalizationInput) {
      return () -> new DefaultNormalizationWorker(
          jobRunConfig.getJobId(),
          Math.toIntExact(jobRunConfig.getAttemptId()),
          NormalizationRunnerFactory.create(
              destinationLauncherConfig.getDockerImage(),
              pbf,
              normalizationInput.getDestinationConfiguration()));
    }

  }

}
