import json
import sys
import os
from datetime import datetime

input_file = sys.argv[1]
output_file = sys.argv[2]

if not os.path.exists(input_file) or os.path.getsize(input_file) == 0:
    print("[INFO] No issues found or file is empty. Creating empty output.")
    with open(output_file, "w") as f:
        json.dump([], f, indent=2)
    sys.exit(0)

try:
    with open(input_file) as f:
        data = json.load(f)
except json.JSONDecodeError:
    print("[ERROR] Failed to parse JSON. Input file is invalid.")
    sys.exit(1)

# Grab Jenkins metadata from environment
jenkins_job = os.getenv("JOB_NAME", "unknown_job")
build_number = os.getenv("BUILD_NUMBER", "0")
jenkins_base = os.getenv("JENKINS_URL", "https://horizonrelevance.com/jenkins")
jenkins_url = f"{jenkins_base}/job/{jenkins_job}/{build_number}/console"

sast_keywords = {
    "sql injection": "Critical",
    "xss": "High",
    "cross-site scripting": "High",
    "hardcoded password": "High",
    "command injection": "Critical",
    "insecure deserialization": "Critical",
    "csrf": "Medium",
    "open redirect": "Medium",
    "path traversal": "High",
    "buffer overflow": "Critical",
    "sensitive data exposure": "High"
}

risk_score_map = {
    "Low": 1,
    "Medium": 2,
    "High": 3,
    "Critical": 4
}

processed = []
fail_pipeline = False

for issue in data.get("issues", []):
    message = issue.get("message", "").lower()
    prediction = "Low"

    for keyword, severity in sast_keywords.items():
        if keyword in message:
            prediction = severity
            break

    if issue.get("severity") in ["CRITICAL", "HIGH"]:
        fail_pipeline = True

    risk_score = risk_score_map.get(prediction, 1)

    processed.append({
        "target": issue.get("component", ""),
        "package_name": "SonarQube Code Scan",
        "installed_version": "N/A",
        "vulnerability_id": issue.get("rule", ""),
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
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "jenkins_job": jenkins_job,
        "build_number": int(build_number),
        "jenkins_url": jenkins_url
    })

with open(output_file, "w") as f:
    json.dump(processed, f, indent=2)

if fail_pipeline:
    print("FAIL_PIPELINE=true")
else:
    print("FAIL_PIPELINE=false")
