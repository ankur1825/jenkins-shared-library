from fastapi import FastAPI
from pydantic import BaseModel
from typing import List

app = FastAPI()

class RiskInput(BaseModel):
    source: str         # "OPA" or "Trivy"
    target: str
    violation: str      # Can be message or CVE ID

class RiskOutput(BaseModel):
    source: str
    target: str
    violation: str
    severity: str
    risk_score: float

@app.post("/score", response_model=List[RiskOutput])
def score_risks(inputs: List[RiskInput]):
    results = []

    for item in inputs:
        violation = item.violation.lower()

        # Simple static scoring logic
        if "secret" in violation:
            severity = "Critical"
            score = 9.8
        elif "root user" in violation or "privileged" in violation:
            severity = "High"
            score = 7.5
        elif "cve" in violation and "2025" in violation:
            severity = "High"
            score = 8.5
        elif "port 22" in violation:
            severity = "Medium"
            score = 6.0
        else:
            severity = "Low"
            score = 4.0

        results.append(RiskOutput(
            source=item.source,
            target=item.target,
            violation=item.violation,
            severity=severity,
            risk_score=score
        ))

    return results
