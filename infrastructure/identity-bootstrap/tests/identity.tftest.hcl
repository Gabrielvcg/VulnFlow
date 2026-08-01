mock_provider "aws" {}

run "operator_is_exact_and_mfa_is_not_invented" {
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
    condition     = length(aws_iam_policy.operator) == 2 && length(aws_iam_role_policy_attachment.operator) == 2
    error_message = "The identity bootstrap must create only the state and application policies and attachments."
  }

  assert {
    condition = (
      jsondecode(aws_iam_role.operator.assume_role_policy).Statement[0].Principal.AWS ==
      "arn:aws:iam::160172542031:user/vacaro"
    )
    error_message = "The trust policy must name only the exact bootstrap IAM user."
  }

  assert {
    condition     = !strcontains(aws_iam_role.operator.assume_role_policy, "aws:MultiFactorAuthPresent")
    error_message = "MFA must not be claimed until the trusted IAM user has a real MFA device."
  }

  assert {
    condition = alltrue(flatten([
      for statement in jsondecode(aws_iam_policy.operator["app"].policy).Statement : [
        for action in try(tolist(statement.Action), [statement.Action]) : action != "*"
      ]
    ]))
    error_message = "The application operator policy must never grant a wildcard action."
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
}

run "real_mfa_serial_enables_trust_condition" {
  command = plan

  variables {
    mfa_serial = "arn:aws:iam::160172542031:mfa/vacaro"
  }

  assert {
    condition     = strcontains(aws_iam_role.operator.assume_role_policy, "aws:MultiFactorAuthPresent")
    error_message = "A reviewed real MFA serial must enable the trust-policy MFA condition."
  }
}
