/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.sync;

import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.StandardSyncOutput;
import io.airbyte.scheduler.models.IntegrationLauncherConfig;
import io.airbyte.scheduler.models.JobRunConfig;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ReplicatorWorkflow {

  @WorkflowMethod
  StandardSyncOutput run(JobRunConfig jobRunConfig,
                         IntegrationLauncherConfig sourceLauncherConfig,
                         IntegrationLauncherConfig destinationLauncherConfig,
                         StandardSyncInput syncInput);

}