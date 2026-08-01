mock_provider "aws" {}

run "operator_is_exact_and_requires_reviewed_mfa" {
  command = plan

  assert {
    condition     = aws_iam_role.operator.name == "VulnFlowTerraformOperator"
    error_message = "The human operator role name is part of the preflight contract."
  }

  assert {
    condition     = aws_iam_role.operator.max_session_duration == 3600
    error_message = "Human operator sessions must remain bounded to one hour."
  }

  assert {
    condition     = length(aws_iam_policy.operator) == 3 && length(aws_iam_role_policy_attachment.operator) == 3
    error_message = "The identity bootstrap must create only state, application, and workload-identity policies and attachments."
  }

  assert {
    condition = (
      jsondecode(aws_iam_role.operator.assume_role_policy).Statement[0].Principal.AWS ==
      "arn:aws:iam::160172542031:user/vacaro"
    )
    error_message = "The trust policy must name only the exact bootstrap IAM user."
  }

  assert {
    condition = (
      jsondecode(aws_iam_role.operator.assume_role_policy).Statement[0].Condition.Bool["aws:MultiFactorAuthPresent"] == "true"
    )
    error_message = "The operator trust policy must require MFA."
  }

  assert {
    condition     = var.mfa_serial == "arn:aws:iam::160172542031:mfa/movil"
    error_message = "The bootstrap must use the reviewed MFA device assigned to vacaro."
  }

  assert {
    condition = alltrue(flatten([
      for policy in aws_iam_policy.operator : [
        for statement in jsondecode(policy.policy).Statement : [
          for action in try(tolist(statement.Action), [statement.Action]) : action != "*"
        ]
      ]
    ]))
    error_message = "No operator policy may grant a wildcard action."
  }

  assert {
    condition = (
      length([
        for statement in jsondecode(aws_iam_policy.operator["app"].policy).Statement : statement
        if statement.Action == "iam:PassRole"
      ]) == 1 &&
      one([
        for statement in jsondecode(aws_iam_policy.operator["app"].policy).Statement : statement
        if statement.Sid == "PassOnlyProcessorRoleToLambda"
      ]).Resource == "arn:aws:iam::160172542031:role/vulnflow-demo-processor-role" &&
      one([
        for statement in jsondecode(aws_iam_policy.operator["app"].policy).Statement : statement
        if statement.Sid == "PassOnlyProcessorRoleToLambda"
      ]).Condition.StringEquals["iam:PassedToService"] == "lambda.amazonaws.com"
    )
    error_message = "PassRole must target only the exact processor role and Lambda service."
  }

  assert {
    condition = (
      one([
        for statement in jsondecode(aws_iam_policy.operator["workload"].policy).Statement : statement
        if statement.Sid == "PassOnlyBackendRoleToRolesAnywhere"
      ]).Resource == "arn:aws:iam::160172542031:role/vulnflow-demo-backend-role" &&
      one([
        for statement in jsondecode(aws_iam_policy.operator["workload"].policy).Statement : statement
        if statement.Sid == "PassOnlyBackendRoleToRolesAnywhere"
      ]).Condition.StringEquals["iam:PassedToService"] == "rolesanywhere.amazonaws.com"
    )
    error_message = "PassRole must target only the exact backend role and Roles Anywhere service."
  }
}
