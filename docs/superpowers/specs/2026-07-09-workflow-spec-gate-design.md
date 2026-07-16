# Workflow Spec Gate Design

## Goal

Natural-language workflow generation should not jump straight from one sentence to a runnable canvas. It should first turn the user's intent into a locked business specification, ask clarifying questions when the boundary is unclear, and only generate the workflow after the specification is ready.

## Scope

This is a product-level spec gate, inspired by OpenSpec's proposal/spec/design/task flow, not a full OpenSpec file-system integration. The first version adds one backend specification-draft endpoint and reuses the existing workflow generation endpoint after a specification is ready.

## User Flow

1. The user opens the intelligent workflow builder and writes a natural-language requirement.
2. The frontend sends the text to the specification draft endpoint.
3. If the model finds missing decisions, the UI shows the questions and tells the user to answer them in the same input box.
4. If the model can lock the requirement, the UI shows a "locked specification" card and changes the primary action to "generate from specification".
5. A second click sends the locked generation prompt to the existing streaming workflow generator.
6. The existing generator still normalizes, validates, repairs, and automatically smoke-tests the workflow before it appears on the canvas.

## Specification Contract

The backend returns:

- `status`: `NEEDS_CLARIFICATION` or `READY`.
- `questions`: concise user-facing clarification questions. Empty when ready.
- `summary`: one-sentence understanding of the workflow goal.
- `spec`: locked business specification fields including goal, inputs, routing rules, actions, integrations, failure policy, output contract, test cases, and non-goals.
- `generationPrompt`: a compact prompt for the existing workflow generator. Present only when ready.

## Boundaries

- The first version does not persist specs as separate database records.
- The first version does not require a dedicated multi-turn backend session. The user can answer questions by editing the same text area.
- Existing `/api/workflows/generate/stream` remains the only endpoint that creates workflow JSON.
- The spec gate is skipped for natural-language editing of an existing canvas for now; editing already has more context and should get a separate gate later.

## Testing

- Unit test the spec service for clarification and ready states.
- Web controller test the new route.
- Static frontend test that the workbench exposes the endpoint and the two-stage UI functions.
- Run existing workflow generation tests to make sure the generation path still works.
