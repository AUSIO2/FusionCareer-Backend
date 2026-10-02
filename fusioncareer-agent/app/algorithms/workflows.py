"""Execute the same editable algorithm presets from HTTP and crawler workflows."""

import copy

from fastapi import HTTPException

from app.engine import WorkflowNodeError


async def run_algorithm_workflow(engine, catalog, name: str, body: dict) -> dict:
    workflow = copy.deepcopy(catalog.get(name))
    workflow["nodes"]["input"]["inputs"]["json_obj"] = {"value": body}
    errors = engine.validate(workflow, allow_source_literals_only=True)
    if errors:
        raise HTTPException(status_code=422, detail={"errors": errors})
    try:
        outputs = await engine.run(workflow)
    except WorkflowNodeError as error:
        # Preserve Java's existing HTTP and backend error contract.
        raise error.cause from error
    return outputs["result.result"]
