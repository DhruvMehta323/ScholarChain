# Bounded pipeline state machine (documented choice: the BOUNDED PIPELINE, not the asynchronous query path).
#   1. CreateCluster  : EMR (Hadoop + Spark) with auto-termination (idle timeout) and managed scaling min 1 / max 2 core+task nodes (+1 primary = 3 nodes), logs to S3.
#                       There is no always-on cluster: the cluster exists only while an execution runs, then idles out.
#   2. RunSteps       : one EMR step at a time (command-runner.jar), taken from the execution input:
#                       { "steps": [ { "name": "...", "args": ["spark-submit", "...", "s3://..."] }, ... ] }
#                       The Phase 13/14 scripts pass the Hadoop, Spark and load steps here.
# The state machine never terminates the cluster itself: EMR auto-termination does (guard rule: no terminate-clusters).

locals {
  cluster_params = {
    Name         = "${var.name}-pipeline"
    ReleaseLabel = var.release_label
    Applications = [for a in var.applications : { Name = a }]
    ServiceRole  = var.service_role_name
    JobFlowRole  = var.instance_profile_name
    LogUri       = "s3://${var.logs_bucket_name}/emr/"
    # Required by the AWS-managed AmazonEMRServicePolicy_v2 on the cluster itself.
    Tags = concat(
      [{ Key = "for-use-with-amazon-emr-managed-policies", Value = "true" }],
      [for k, v in var.tags : { Key = k, Value = v }]
    )
    VisibleToAllUsers = true
    Instances = {
      KeepJobFlowAliveWhenNoSteps   = true # so steps can be added; AutoTerminationPolicy ends the cluster when idle
      TerminationProtected          = false
      Ec2SubnetId                   = var.subnet_id
      EmrManagedMasterSecurityGroup = var.security_group_id
      EmrManagedSlaveSecurityGroup  = var.security_group_id
      InstanceGroups = [
        { Name = "Master", InstanceRole = "MASTER", InstanceType = var.instance_type, InstanceCount = 1, Market = "ON_DEMAND" },
        { Name = "Core", InstanceRole = "CORE", InstanceType = var.instance_type, InstanceCount = var.core_instance_count, Market = "ON_DEMAND" },
      ]
    }
    AutoTerminationPolicy = { IdleTimeout = var.idle_timeout_seconds }
    ManagedScalingPolicy = {
      ComputeLimits = {
        UnitType                     = "Instances"
        MinimumCapacityUnits         = 1
        MaximumCapacityUnits         = var.max_capacity_units
        MaximumOnDemandCapacityUnits = var.max_capacity_units
        MaximumCoreCapacityUnits     = var.max_capacity_units
      }
    }
    StepConcurrencyLevel = 1
  }

  definition = {
    Comment        = "ScholarChain bounded pipeline: EMR cluster (auto-terminating) then sequential steps"
    StartAt        = "CreateCluster"
    TimeoutSeconds = var.execution_timeout_seconds
    States = {
      CreateCluster = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:createCluster.sync"
        Parameters     = local.cluster_params
        ResultPath     = "$.cluster"
        TimeoutSeconds = 1800
        Next           = "RunSteps"
      }
      RunSteps = {
        Type           = "Map"
        ItemsPath      = "$.steps"
        MaxConcurrency = 1
        ItemSelector = {
          "clusterId.$" = "$.cluster.ClusterId"
          "step.$"      = "$$.Map.Item.Value"
        }
        ItemProcessor = {
          ProcessorConfig = { Mode = "INLINE" }
          StartAt         = "AddStep"
          States = {
            AddStep = {
              Type           = "Task"
              TimeoutSeconds = var.step_timeout_seconds
              Resource       = "arn:aws:states:::elasticmapreduce:addStep.sync"
              Parameters = {
                "ClusterId.$" = "$.clusterId"
                Step = {
                  "Name.$"        = "$.step.name"
                  ActionOnFailure = "CANCEL_AND_WAIT"
                  HadoopJarStep = {
                    Jar      = "command-runner.jar"
                    "Args.$" = "$.step.args"
                  }
                }
              }
              End = true
            }
          }
        }
        ResultPath = "$.stepResults"
        End        = true
      }
    }
  }
}

resource "aws_sfn_state_machine" "pipeline" {
  name     = var.state_machine_name
  role_arn = var.sfn_role_arn
  type     = "STANDARD"

  definition = jsonencode(local.definition)

  logging_configuration {
    log_destination        = "${var.log_group_arn}:*"
    include_execution_data = false
    level                  = "ERROR"
  }
}
