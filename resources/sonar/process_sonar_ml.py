import json
import sys
import os
from datetime import datetime

input_file = sys.argv[1]
output_file = sys.argv[2]

def predict_severity(message):
    message = message.lower()
    if "sql injection" in message or "command injection" in message or "buffer overflow" in message:
        return "Critical"
    elif "xss" in message or "cross-site scripting" in message or "hardcoded password" in message:
        return "High"
    elif "csrf" in message or "open redirect" in message:
        return "Medium"
    return "Low"

risk_score_map = {
    "Low": 1,
    "Medium": 2,
    "High": 3,
    "Critical": 4
}

if not os.path.exists(input_file) or os.path.getsize(input_file) == 0:
    print("[INFO] No issues found or file is empty. Creating empty output.")
    with open(output_file, "w") as f:
        json.dump([], f, indent=2)
    print("FAIL_PIPELINE=false")
    sys.exit(0)

try:
    with open(input_file) as f:
        data = json.load(f)
except json.JSONDecodeError:
    print("[ERROR] Failed to parse JSON.")
    sys.exit(1)

processed = []
fail_pipeline = False

for issue in data.get("issues", []):
    prediction = predict_severity(issue.get("message", ""))
    risk_score = risk_score_map[prediction]

    if prediction in ["High", "Critical"]:
        fail_pipeline = True

    processed.append({
        "target": issue.get("component", ""),
        "package_name": "SonarQube Code Smell",
        "installed_version": "N/A",
        "vulnerability_id": issue.get("rule", "undefined"),
        "message": issue.get("message", ""),
        "line": issue.get("line", 0),
        "type": issue.get("type", ""),
        "severity": issue.get("severity", ""),
        "predictedSeverity": prediction,
        "riskScore": risk_score,
        "rule": issue.get("rule", ""),
        "status": issue.get("status", ""),
        "author": issue.get("author", ""),
        "source": "SonarQube",
        "timestamp": datetime.utcnow().isoformat()
    })

with open(output_file, "w") as f:
    json.dump(processed, f, indent=2)

print("FAIL_PIPELINE=" + str(fail_pipeline).lower())
