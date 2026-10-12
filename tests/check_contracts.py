import json
import sys
from pathlib import Path
import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate_spec

root = Path(__file__).resolve().parents[1]
contract = yaml.safe_load((root / "docs/openapi.yaml").read_text(encoding="utf-8"))
validate_spec(contract, base_uri=(root / "docs/openapi.yaml").as_uri())
event_schema = json.loads((root / "docs/stock-changed.schema.json").read_text(encoding="utf-8"))
Draft202012Validator.check_schema(event_schema)
if len(sys.argv) > 1:
    samples = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    for sample in samples:
        schema = {"$ref": "#/components/schemas/" + sample["schema"], "components": contract["components"]}
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(sample["body"])
print("PASS: OpenAPI and supplied response schemas")
