# Feature specification: <name>

Create one feature specification under `docs/features/` before implementing a release feature. Use the release scope and the relevant feature in the implementation plan as inputs; inspect the current implementation before making claims. Keep the specification focused on the feature, and update it with actual verification evidence as work proceeds.

## Goal and status

- **Release feature:** <number and name from the release plan>
- **Status:** Draft / In progress / Complete / Blocked
- **Goal:** <user-visible outcome>
- **Context:** <links to the release scope, plan and relevant existing code>

## Scope

- **Included:** <behaviors this feature delivers>
- **Excluded:** <related work reserved for another feature or release>
- **Dependencies:** <earlier features, environment requirements and user decisions>

## Current behavior

<Describe what the inspected code actually does and identify relevant constraints or known failures. Distinguish observations from assumptions.>

## Proposed behavior and interfaces

<Describe user-facing behavior, state transitions, API requests and responses, persistence and interactions with other services as relevant. Include input limits, failure responses and compatibility implications.>

## Security and privacy

<Describe access checks, handling of untrusted input and sensitive data, and resource limits relevant to this feature. Reference the release scope instead of repeating unrelated requirements.>

## Acceptance criteria

- [ ] <An observable outcome that can be verified>
- [ ] <A relevant failure or security case>

## Test plan

| Test file or check | Scenario and expected result | Command |
| --- | --- | --- |
| <path or manual check> | <behavior to verify> | <command, when applicable> |

Use synthetic or anonymized fixtures. Include meaningful negative and recovery cases where relevant. Identify any check that requires an external dependency or manual review.

## Implementation notes

- **Likely files:** <paths and responsibilities>
- **Order:** <small steps that can be reviewed and tested>
- **Open decisions:** <items requiring user input or a decision before dependent work>

## Verification and handoff

Update this section as work progresses; planned checks are not evidence that they passed.

- **Completed:** <behavior and files changed>
- **Checks run:** <exact commands, results and date>
- **Not verified / blockers:** <reason and practical impact>
- **Next step:** <specific continuation for the next session>
