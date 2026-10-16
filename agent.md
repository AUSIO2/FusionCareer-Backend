# FusionCareer Agent Rules

## 1. Mandatory Ponytail Mode

All coding work in this repository must use the `ponytail` skill in full mode.

Apply this order and stop at the first solution that works:

1. Do not build speculative behavior.
2. Reuse code already present in the repository.
3. Prefer the standard library.
4. Prefer native framework or platform features.
5. Reuse an installed dependency.
6. Add the minimum new code only when the earlier options do not solve the task.

Additional requirements:

- Fix the root cause at the shared execution path, not each visible symptom.
- Do not add speculative abstractions, factories, wrappers, interfaces or configuration.
- Do not add a dependency when the standard library or an installed dependency is sufficient.
- Keep changes in the fewest files possible.
- Non-trivial logic must include one smallest runnable verification.
- Security, input validation, data-loss prevention and accessibility must not be simplified away.

## 2. Variable and Function Naming

New or modified business variables and functions must be short and use this form:

```text
<verb><Noun>
```

Use one concrete action followed by one clear object. Use lower camel case in Java, JavaScript and Python business code.

Good function names:

```text
loadJobs
saveProfile
submitForm
uploadFile
toggleMenu
validateAnswer
```

Good variable names:

```text
displayJobs
selectJobIds
editProfile
showDialog
uploadFile
storeToken
```

Forbidden names:

```text
fetchAndNormalizeJobPostListWithFilters
handleUserProfileFormSubmission
loadAndTransformQuestionnaireResponse
processData
handleThing
jobList
data
result
temp
```

Rules:

- There is no fixed CRUD verb list. Use the natural action: `load`, `save`, `submit`, `upload`, `download`, `validate`, `parse`, `build`, `map`, `format`, `show`, `hide`, `toggle`, `select`, `create`, `read`, `update` or `delete`.
- Prefer two semantic parts. A known domain compound such as `JobPost`, `UserProfile` or `ResumeFile` counts as one object.
- Do not join multiple actions with `And`, `Then`, `With` or similar chains. Split responsibilities instead.
- Do not add implementation details to a name when the object already explains them.
- Avoid vague nouns such as `Data`, `Info`, `Item`, `Thing`, `Object`, `Result` or `Temp` unless that is the actual domain term.
- Boolean variables still use a verb and noun: `showDialog`, `hasToken`, `allowMock`.
- Event functions describe the action, not the event mechanism: `submitForm`, not `handleSubmitClick`.
- Test functions use the same concise form where the framework permits it: `loadJobs`, not `testShouldLoadAllPublishedJobsWhenUserIsLoggedIn`.

The rule applies to business variables and functions introduced or modified by the task. Framework-required names and external serialized field names may remain unchanged only when renaming them would break framework discovery or the agreed API contract. Do not use this exception for convenience.

Do not bulk-rename untouched legacy code. Enforce the rule in the changed scope and migrate surrounding names only when needed for a compiling, coherent change.

## 3. API Contract Rules

- Student browser requests use `/api/**`.
- Administrator browser requests use `/api/admin/**`.
- Python service-to-service requests use Java `/internal/**` directly.
- Browser code must never call `/internal/**`.
- Authentication uses the `Fusion-Token` header.
- Success and error responses use `{code,message,data}` except file streams and redirects.
- Frontend and backend must use the same enum names; do not translate enum values in requests.
- Do not hardcode production hosts, ports, passwords, tokens or API keys.
- Use environment variables for deploy-time addresses and secrets.

## 4. Change and Commit Rules

- Follow `docs/FRONTEND_BACKEND_ALIGNMENT_PLAN.md` for integration work.
- One commit solves one problem and contains its minimum verification.
- Use Conventional Commit messages.
- Keep backend and frontend changes in their own repositories and commits.
- Preserve unrelated user changes in a dirty worktree.
- Never commit `.env`, credentials, generated production data, uploaded files or build output.
- Before committing backend code, run the smallest relevant test and `./mvnw test` when practical.
- Before committing frontend code, run `npm run build` and the smallest relevant check.
- Do not mark an endpoint complete while the UI still uses mock data or a button only shows a fake success message.

## 5. Definition of Done

A change is complete only when:

- The frontend request matches the backend method, path, parameters and body.
- The frontend parser matches the backend response shape.
- Authentication and authorization are enforced by the backend.
- Loading, empty, success and error states are handled.
- Production code has no fallback mock for the completed feature.
- The relevant runnable verification passes.
- Documentation is updated when the shared contract changes.
